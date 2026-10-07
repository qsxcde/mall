package com.geekmall.integration;

import com.geekmall.common.ratelimit.LocalRateLimiter;
import com.geekmall.common.ratelimit.RateLimitPolicies;
import com.geekmall.common.ratelimit.RateLimitPolicy;
import com.geekmall.common.result.ResultCode;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流行为端到端集成测试。
 *
 * <p>这里把限流<b>打开</b>（其余功能用例在 {@code application-test.yml} 里关掉了它），
 * 用真实拦截器 + 真实 Redis 验证：超限返回 429 与 {@code Retry-After}、维度隔离、
 * 窗口恢复、豁免路径、并发槽位释放、以及分布式层真的落在 Redis 上。</p>
 *
 * <h3>为什么用「一直打到被限流为止」而不是「第 N 次必须是 429」</h3>
 * <p>滑动窗口在窗口边界存在残留权重：如果持续请求恰好跨越了窗口边界，
 * 被拒的位置会比阈值靠后。硬断言「第 limit+1 次必须 429」会有小概率偶发失败。
 * 因此这里的断言是两条：<b>被拒之前至少放行了 limit 次</b>（少了说明多计数了），
 * 以及<b>最终确实被拒</b>（说明限流真的生效）。两条都不依赖请求落在窗口的哪个位置。</p>
 *
 * <p>注意：MockMvc 下所有请求的客户端 IP 都是 127.0.0.1，因此每个用例使用<b>不同的规则</b>，
 * 避免互相消耗同一个计数 key。</p>
 */
@SpringBootTest(properties = "mall.rate-limit.enabled=true")
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LocalRateLimiter localRateLimiter;

    @Autowired
    private com.geekmall.common.ratelimit.RateLimitProperties rateLimitProperties;

    @Test
    @DisplayName("配置项正确绑定到 RateLimitProperties（kebab-case 拼错会让限流被静默关闭）")
    void propertiesAreBoundFromConfiguration() {
        assertThat(rateLimitProperties.isEnabled())
                .as("@SpringBootTest 显式打开了开关，绑定失败会读到默认值而掩盖问题")
                .isTrue();
        assertThat(rateLimitProperties.getLocalMaxKeys())
                .as("mall.rate-limit.local-max-keys 必须绑定到 localMaxKeys")
                .isEqualTo(200_000);
    }

    /**
     * 清空分布式限流计数。
     *
     * <p>MockMvc 下所有请求来自同一个 IP，而 {@code registerAndLogin} 这类辅助操作
     * 也会消耗 {@code auth-login} / {@code auth-sms-code} 的额度，用例之间会互相干扰。
     * 因此每个用例开始前把 Redis 里的计数清干净，保证执行顺序无关。</p>
     */
    @BeforeEach
    void resetDistributedCounters() {
        var keys = stringRedisTemplate.keys("mall:rate:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private record Probe(int allowedBeforeReject, MockHttpServletResponse rejected) {
        boolean rejectedAtAll() {
            return rejected != null;
        }
    }

    /** 注意：方法名刻意不与基类的 JSON 辅助方法（get/post）重名，避免签名冲突。 */
    private MockHttpServletResponse rawGet(String url, String token) throws Exception {
        return call(MockMvcRequestBuilders.get(url), token);
    }

    private MockHttpServletResponse rawPost(String url, String token) throws Exception {
        return call(MockMvcRequestBuilders.post(url), token);
    }

    private MockHttpServletResponse call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                                        String token) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder).andReturn().getResponse();
    }

    /** 持续请求直到被限流，返回「被拒之前放行了几次」与那次 429 响应。 */
    private Probe probeUntilLimited(String url, boolean isPost, int maxAttempts) throws Exception {
        for (int i = 0; i < maxAttempts; i++) {
            MockHttpServletResponse response = isPost ? rawPost(url, null) : rawGet(url, null);
            if (limited(response)) {
                return new Probe(i, response);
            }
        }
        return new Probe(maxAttempts, null);
    }

    private static boolean limited(MockHttpServletResponse response) {
        return response.getStatus() == 429;
    }

    private static int limitOf(String method, String path) {
        return new RateLimitPolicies().resolve(method, path).limit();
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("超过阈值返回 429，并下发 Retry-After 与限流余量响应头")
    void rejectsOverLimitWithRetryAfter() throws Exception {
        int limit = limitOf("GET", "/api/v1/cms/about");

        MockHttpServletResponse first = rawGet("/api/v1/cms/about", null);
        assertThat(limited(first)).isFalse();
        assertThat(first.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(limit));
        assertThat(first.getHeader("X-RateLimit-Remaining")).isEqualTo(String.valueOf(limit - 1));

        Probe probe = probeUntilLimited("/api/v1/cms/about", false, limit * 2);

        assertThat(probe.allowedBeforeReject() + 1)
                .as("被拒之前至少应放行 %d 次；明显偏少说明计数被重复累加", limit)
                .isGreaterThanOrEqualTo(limit);
        assertThat(probe.rejectedAtAll()).as("持续打满额度后必须出现 429").isTrue();

        MockHttpServletResponse over = probe.rejected();
        assertThat(over.getHeader("Retry-After")).as("必须告知客户端该退避多久").isNotNull();
        assertThat(Integer.parseInt(over.getHeader("Retry-After"))).isBetween(1, 10);
        assertThat(objectMapper.readTree(over.getContentAsString(StandardCharsets.UTF_8)).get("code").asInt())
                .isEqualTo(ResultCode.TOO_MANY_REQUESTS.getCode());
    }

    @Test
    @DisplayName("按登录主体分组的接口互不牵连：一个用户被限流，另一个用户正常")
    void subjectDimensionIsolatesUsers() throws Exception {
        int limit = limitOf("GET", "/api/v1/user/profile");
        String heavyUser = registerAndLogin(randomPhone());
        String lightUser = registerAndLogin(randomPhone());

        int allowed = 0;
        for (int i = 0; i < limit * 2; i++) {
            MockHttpServletResponse response = rawGet("/api/v1/user/profile", heavyUser);
            if (limited(response)) {
                break;
            }
            allowed++;
        }

        assertThat(allowed).as("额度耗尽前至少放行 %d 次", limit).isGreaterThanOrEqualTo(limit);

        MockHttpServletResponse otherUser = rawGet("/api/v1/user/profile", lightUser);
        assertThat(limited(otherUser)).as("其他用户不应被牵连").isFalse();
        assertThat(otherUser.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("窗口过期后额度恢复（限流不是永久封禁）")
    void recoversAfterWindowExpires() throws Exception {
        int limit = limitOf("GET", "/api/v1/home/floors");

        Probe probe = probeUntilLimited("/api/v1/home/floors", false, limit * 2);
        assertThat(probe.rejectedAtAll()).as("持续请求应最终被限流").isTrue();
        assertThat(probe.allowedBeforeReject()).isGreaterThanOrEqualTo(limit);

        // 静默跨越两个 1 秒窗口：上一窗口的残留权重归零
        Thread.sleep(2200);

        for (int i = 0; i < 20; i++) {
            assertThat(limited(rawGet("/api/v1/home/floors", null)))
                    .as("窗口过期后第 %d 次请求应被放行", i + 1)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("豁免路径（健康检查）不会被限流打挂，否则监控会误判服务故障")
    void exemptPathsAreNeverLimited() throws Exception {
        // 先确认健康检查确实可达，否则「没有 429」可能只是因为请求根本没到后端
        assertThat(rawGet("/actuator/health", null).getStatus())
                .as("健康检查应可访问，本用例才有意义")
                .isEqualTo(200);

        int limitedCount = 0;
        for (int i = 0; i < 300; i++) {
            if (limited(rawGet("/actuator/health", null))) {
                limitedCount++;
            }
        }

        assertThat(limitedCount).as("健康检查属于豁免路径，不应出现任何 429").isZero();
    }

    @Test
    @DisplayName("并发槽位在请求结束后释放，不会泄漏导致接口被永久限死")
    void concurrencySlotsAreReleasedAfterEachRequest() throws Exception {
        // 必须带令牌：未登录的请求会被 Spring Security 先行拒绝，根本到不了限流拦截器，
        // 那样测的就不是槽位释放，而是一条空路径。
        String token = registerAndLogin(randomPhone());
        long userId = buyerIdOf(token);
        String concurrencyKey = "mall:rate:trade-submit:u:" + userId + ":conc";
        String body = "{}";

        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < CONCURRENCY_ROUND; i++) {
                MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/trade/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn().getResponse();
                assertThat(limited(response))
                        .as("参数不合法应被业务层拒绝（400），而不是被并发限流挡下")
                        .isFalse();
                assertThat(localRateLimiter.inFlightCount(concurrencyKey))
                        .as("请求结束后并发槽位必须归还，否则第 %d 轮之后就会被永久 429", round + 1)
                        .isZero();
            }
            // 静默跨越两个窗口：滑动窗口会把上一窗口的计数按权重带入新窗口，
            // 只等 1 个窗口的话第二轮仍会被上一轮的残留计数拦住。
            Thread.sleep(2200);
        }
    }

    /**
     * 每轮批量。
     *
     * <p>取 3（明显小于 {@code trade-submit} 的 5 次/秒阈值）：这样即使请求恰好跨越
     * 窗口边界，上一窗口的残留权重也不会把本轮的请求判成超限 ——
     * 本用例要验证的是「并发槽位有没有泄漏」，不该被频率限流的边界行为干扰。</p>
     */
    private static final int CONCURRENCY_ROUND = 3;

    /** 取当前令牌对应的买家 ID，用于拼出并发槽位的 key。 */
    private long buyerIdOf(String token) {
        return assertSuccess(get("/api/v1/user/profile", token)).get("id").asLong();
    }

    @Test
    @DisplayName("分布式层真实落在 Redis 上，并且计数 key 带有过期时间")
    void distributedTierStoresCounterInRedisWithTtl() throws Exception {
        RateLimitPolicy policy = new RateLimitPolicies().resolve("POST", "/api/v1/auth/sms-code");
        String redisKey = "mall:rate:" + policy.name() + ":ip:127.0.0.1";

        assertThat(limited(rawPost("/api/v1/auth/sms-code", null))).isFalse();

        assertThat(stringRedisTemplate.hasKey(redisKey))
                .as("分布式规则的计数必须落在 Redis 中（多实例才能全局准确）")
                .isTrue();
        Long ttl = stringRedisTemplate.getExpire(redisKey);
        assertThat(ttl).as("计数 key 必须有 TTL，否则会变成对该 IP 的永久封禁")
                .isNotNull()
                .isBetween(1L, 61L);

        Probe probe = probeUntilLimited("/api/v1/auth/sms-code", true, policy.limit() * 2);
        assertThat(probe.rejectedAtAll()).as("超过 %d 次/窗口后应返回 429", policy.limit()).isTrue();
    }

    @Test
    @DisplayName("异步端点（秒杀）按请求计数而不是按分发计数，额度不会被吃掉一半")
    void asyncEndpointCountsOncePerRequest() throws Exception {
        // 秒杀接口返回 DeferredResult，是全站唯一的异步端点。
        // 若异步二次分发也被计数，额度会在远小于阈值时就被打满。
        // 必须带令牌：否则请求会被 Spring Security 挡在限流拦截器之前。
        String token = registerAndLogin(randomPhone());
        int limit = limitOf("POST", "/api/v1/seckill/1/order");

        int allowed = 0;
        boolean rejected = false;
        for (int i = 0; i < limit + 20; i++) {
            MockHttpServletResponse response = rawPost("/api/v1/seckill/1/order", token);
            if (limited(response)) {
                rejected = true;
                break;
            }
            allowed++;
        }

        assertThat(allowed)
                .as("异步分发若被重复计数，这里会远小于阈值 %d", limit)
                .isGreaterThanOrEqualTo(limit);
        assertThat(rejected).as("额度用尽后应出现 429").isTrue();
    }

    @Test
    @DisplayName("未登录访问受保护端点会被鉴权层先行拒绝，不会进入限流计数（设计边界）")
    void unauthenticatedRequestsAreRejectedBeforeRateLimiting() throws Exception {
        // 这是一条刻意固化的边界：限流拦截器位于 DispatcherServlet 内部，
        // 而 Spring Security 的过滤器链更靠前。因此「受保护端点 + 无令牌」的请求
        // 由鉴权层以更低成本拒绝（401），不会消耗限流额度，也不会污染其他用户的计数。
        String unauthenticatedKey = "mall:rate:trade-submit:ip:127.0.0.1";

        for (int i = 0; i < 20; i++) {
            MockHttpServletResponse response = rawPost("/api/v1/trade/orders", null);
            assertThat(limited(response))
                    .as("鉴权层已拒绝，不应再叠加限流 429")
                    .isFalse();
        }

        assertThat(stringRedisTemplate.hasKey(unauthenticatedKey))
                .as("未通过鉴权的请求不应产生限流计数")
                .isFalse();
        assertThat(localRateLimiter.inFlightCount(unauthenticatedKey + ":conc")).isZero();
    }

    @Test
    @DisplayName("参数校验/业务失败的请求同样计入限流（防的是请求量，不是成功业务量）")
    void invalidRequestsAreAlsoCounted() throws Exception {
        int limit = limitOf("POST", "/api/v1/auth/login");
        String body = objectMapper.writeValueAsString(
                Map.of("account", "13800000000", "password", "wrong-pwd"));

        int allowed = 0;
        MockHttpServletResponse rejected = null;
        for (int i = 0; i < limit * 2; i++) {
            MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn().getResponse();
            if (limited(response)) {
                rejected = response;
                break;
            }
            allowed++;
        }

        assertThat(allowed)
                .as("业务失败（密码错误）的请求也应被计数，否则撞库可以无限尝试")
                .isGreaterThanOrEqualTo(limit);
        assertThat(rejected).as("达到阈值后应限流").isNotNull();
    }
}
