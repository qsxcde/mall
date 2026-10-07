package com.geekmall.common.ratelimit;

import com.geekmall.security.LoginUser;
import com.geekmall.security.MerchantLoginUser;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 限流拦截器单元测试，专门覆盖「不该计数」与「该计数」的边界。
 *
 * <p>限流最典型的线上事故不是阈值配错，而是<b>计数口径错了</b>：
 * 异步请求被计两次、预检请求吃掉额度、健康检查被判故障。
 * 这些用例就是把口径钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private LocalRateLimiter localRateLimiter;
    @Mock
    private DistributedRateLimiter distributedRateLimiter;

    private RateLimitProperties properties;
    private RateLimitInterceptor interceptor;

    /** 供 HandlerMethod 使用的占位控制器。 */
    public static class Fixture {
        @SuppressWarnings("unused")
        public void handle() {
            // 仅用于构造 HandlerMethod
        }
    }

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        interceptor = new RateLimitInterceptor(properties, new RateLimitPolicies(),
                localRateLimiter, distributedRateLimiter);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static HandlerMethod handlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new Fixture(), Fixture.class.getDeclaredMethod("handle"));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private static void loginAsBuyer(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "13800000000", "买家"), null, List.of()));
    }

    private static void loginAsMerchant(long merchantUserId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new MerchantLoginUser(merchantUserId, "merchant", 1L, "店铺"), null, List.of()));
    }

    @Nested
    @DisplayName("不参与计数的场景")
    class Bypassed {

        @Test
        @DisplayName("总开关关闭时完全放行（测试环境靠它避免与业务用例互相干扰）")
        void disabledBySwitch() throws Exception {
            properties.setEnabled(false);

            boolean result = interceptor.preHandle(request("GET", "/api/v1/products"), new MockHttpServletResponse(),
                    handlerMethod());

            assertThat(result).isTrue();
            verifyNoInteractions(localRateLimiter, distributedRateLimiter);
        }

        @Test
        @DisplayName("异步二次分发不计数：否则一次抢购会被算两次，阈值凭空砍半")
        void asyncDispatchIsNotCounted() throws Exception {
            MockHttpServletRequest request = request("POST", "/api/v1/seckill/1/order");
            request.setDispatcherType(DispatcherType.ASYNC);

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod());

            assertThat(result).isTrue();
            verifyNoInteractions(localRateLimiter, distributedRateLimiter);
        }

        @Test
        @DisplayName("错误分发（ERROR）不计数")
        void errorDispatchIsNotCounted() throws Exception {
            MockHttpServletRequest request = request("GET", "/error");
            request.setDispatcherType(DispatcherType.ERROR);

            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod())).isTrue();
            verifyNoInteractions(localRateLimiter, distributedRateLimiter);
        }

        @Test
        @DisplayName("跨域预检 OPTIONS 不计数（浏览器自动发起，与用户行为无关）")
        void optionsIsNotCounted() throws Exception {
            assertThat(interceptor.preHandle(request("OPTIONS", "/api/v1/products"),
                    new MockHttpServletResponse(), handlerMethod())).isTrue();
            verifyNoInteractions(localRateLimiter, distributedRateLimiter);
        }

        @Test
        @DisplayName("健康检查/文档/静态资源豁免（否则监控抓取频率一高就被判故障）")
        void exemptPathsAreNotCounted() throws Exception {
            for (String path : List.of("/actuator/health", "/v3/api-docs", "/swagger-ui/index.html",
                    "/uploads/2026/10/07/a.png", "/error")) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(interceptor.preHandle(request("GET", path), response, handlerMethod()))
                        .as("%s 应放行", path)
                        .isTrue();
            }
            verifyNoInteractions(localRateLimiter, distributedRateLimiter);
        }

        @Test
        @DisplayName("非控制器处理器（静态资源等）放行，不参与限流")
        void nonHandlerMethodIsNotCounted() throws Exception {
            boolean result = interceptor.preHandle(request("GET", "/api/v1/products"),
                    new MockHttpServletResponse(), new Object());

            assertThat(result).isTrue();
            verifyNoInteractions(localRateLimiter, distributedRateLimiter);
        }
    }

    @Nested
    @DisplayName("计数与响应头")
    class Counting {

        @Test
        @DisplayName("放行时写入限流响应头，便于前端与压测工具自适应退避")
        void writesRateLimitHeaders() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(200, 199));
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(request("GET", "/api/v1/products"), response, handlerMethod());

            assertThat(result).isTrue();
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("200");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("199");
        }

        @Test
        @DisplayName("本地规则走本地限流器，且使用策略表里的阈值与窗口")
        void usesLocalLimiterWithPolicyThresholds() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(200, 1));

            interceptor.preHandle(request("GET", "/api/v1/products"), new MockHttpServletResponse(), handlerMethod());

            verify(localRateLimiter).tryAcquire(anyString(), eq(200), eq(1));
            verify(distributedRateLimiter, never()).tryAcquire(anyString(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("分布式规则走 Redis 限流器，并带上策略声明的降级语义")
        void usesDistributedLimiterForCriticalEndpoints() throws Exception {
            when(distributedRateLimiter.tryAcquire(anyString(), anyInt(), anyInt(), any()))
                    .thenReturn(RateLimitResult.allow(10, 9));

            interceptor.preHandle(request("POST", "/api/v1/auth/login"), new MockHttpServletResponse(),
                    handlerMethod());

            verify(distributedRateLimiter).tryAcquire(anyString(), eq(10), eq(60),
                    eq(RateLimitFallback.FAIL_CLOSED));
        }

        @Test
        @DisplayName("超限抛限流异常，并携带重试等待秒数（由全局异常处理器转 429 + Retry-After）")
        void throwsWhenOverLimit() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.reject(200, 7));

            assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/v1/products"),
                    new MockHttpServletResponse(), handlerMethod()))
                    .isInstanceOf(RateLimitException.class)
                    .hasMessageContaining("系统繁忙")
                    .satisfies(e -> assertThat(((RateLimitException) e).getRetryAfterSeconds()).isEqualTo(7));
        }
    }

    @Nested
    @DisplayName("维度解析")
    class Dimension {

        @Test
        @DisplayName("SUBJECT 维度优先用买家 ID 分组，同一出口 IP 下的不同用户互不影响")
        void subjectUsesBuyerId() throws Exception {
            loginAsBuyer(7L);
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(10, 9));

            interceptor.preHandle(request("GET", "/api/v1/orders"), new MockHttpServletResponse(), handlerMethod());

            assertThat(capturedKey()).isEqualTo("mall:rate:order-read:u:7");
        }

        @Test
        @DisplayName("SUBJECT 维度在买家未登录时回退到商家身份")
        void subjectUsesMerchantId() throws Exception {
            loginAsMerchant(3L);
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(60, 59));

            interceptor.preHandle(request("GET", "/api/v1/merchant/overview"), new MockHttpServletResponse(),
                    handlerMethod());

            assertThat(capturedKey()).isEqualTo("mall:rate:merchant-read:m:3");
        }

        @Test
        @DisplayName("SUBJECT 维度在完全匿名时回退到 IP，保证匿名流量同样被约束")
        void subjectFallsBackToIp() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(10, 9));

            interceptor.preHandle(request("GET", "/api/v1/orders"), new MockHttpServletResponse(), handlerMethod());

            assertThat(capturedKey()).isEqualTo("mall:rate:order-read:ip:127.0.0.1");
        }

        @Test
        @DisplayName("IP 维度取代理头里的首个地址，而不是网关地址")
        void ipDimensionUsesForwardedHeader() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(60, 59));
            MockHttpServletRequest request = request("GET", "/api/v1/cms/about");
            request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

            interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod());

            assertThat(capturedKey()).isEqualTo("mall:rate:content-read:ip:203.0.113.9");
        }

        private String capturedKey() {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(localRateLimiter).tryAcquire(captor.capture(), anyInt(), anyInt());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("并发槽位的获取与释放")
    class Concurrency {

        @Test
        @DisplayName("并发已满时拒绝，避免慢请求把线程与连接池耗光")
        void rejectsWhenConcurrencyFull() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(5, 4));
            when(localRateLimiter.tryAcquireConcurrent(anyString(), eq(10))).thenReturn(false);

            assertThatThrownBy(() -> interceptor.preHandle(request("POST", "/api/v1/trade/orders"),
                    new MockHttpServletResponse(), handlerMethod()))
                    .isInstanceOf(RateLimitException.class)
                    .hasMessageContaining("请求过多");
        }

        @Test
        @DisplayName("同步请求结束时释放槽位，且重复调用不会重复释放")
        void releasesOnCompletionExactlyOnce() throws Exception {
            MockHttpServletRequest request = request("POST", "/api/v1/trade/orders");
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(5, 4));
            when(localRateLimiter.tryAcquireConcurrent(anyString(), eq(10))).thenReturn(true);

            interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod());
            interceptor.afterCompletion(request, new MockHttpServletResponse(), handlerMethod(), null);
            interceptor.afterCompletion(request, new MockHttpServletResponse(), handlerMethod(), null);

            verify(localRateLimiter).releaseConcurrent(anyString());
            verify(localRateLimiter).tryAcquireConcurrent("mall:rate:trade-submit:ip:127.0.0.1:conc", 10);
        }

        @Test
        @DisplayName("异步请求走 afterConcurrentHandlingStarted 释放，否则槽位泄漏会把接口永久限死")
        void releasesOnAsyncStart() throws Exception {
            MockHttpServletRequest request = request("POST", "/api/v1/trade/orders");
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(5, 4));
            when(localRateLimiter.tryAcquireConcurrent(anyString(), eq(10))).thenReturn(true);

            interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod());
            interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), handlerMethod());

            verify(localRateLimiter).releaseConcurrent(anyString());
        }

        @Test
        @DisplayName("未占用槽位的请求在结束时不需要释放")
        void doesNotReleaseWhenNothingAcquired() throws Exception {
            when(localRateLimiter.tryAcquire(anyString(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.allow(200, 199));
            MockHttpServletRequest request = request("GET", "/api/v1/products");

            interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethod());
            interceptor.afterCompletion(request, new MockHttpServletResponse(), handlerMethod(), null);

            verify(localRateLimiter, never()).releaseConcurrent(anyString());
        }
    }
}
