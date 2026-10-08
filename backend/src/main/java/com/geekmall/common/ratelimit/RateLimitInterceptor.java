package com.geekmall.common.ratelimit;

import com.geekmall.common.cluster.ClusterProperties;
import com.geekmall.security.MerchantSecurityUtils;
import com.geekmall.security.SecurityUtils;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 全站接口限流拦截器。
 *
 * <p>本身不含任何规则：规则全部来自 {@link RateLimitPolicies}，这里只负责
 * 「取规则 → 定维度 → 按层级执行 → 写响应头 / 抛 429」。</p>
 *
 * <h3>边界情况处理（逐条对应）</h3>
 * <ol>
 *   <li><b>异步二次分发不重复计数</b>：秒杀接口返回 {@code DeferredResult}，
 *       Servlet 会以 {@code ASYNC} 类型再走一次过滤链与 DispatcherServlet。
 *       若不加限制，一次抢购会被计两次，等于把阈值凭空砍半。
 *       因此这里只对最初的 {@code REQUEST} 分发计数。</li>
 *   <li><b>OPTIONS 预检不计数</b>：跨域预检由浏览器自动发起，与用户行为无关，
 *       计数会误伤正常页面。</li>
 *   <li><b>运维与静态资源豁免</b>：见 {@link RateLimitPolicies#isExempt}，
 *       否则健康检查被抓取频率一高就会被判故障。</li>
 *   <li><b>非控制器处理器放行</b>：静态资源等由 {@code ResourceHttpRequestHandler} 处理，
 *       不消耗后端计算资源，不参与限流。</li>
 *   <li><b>维度回退</b>：未登录时 SUBJECT 维度回退到 IP，保证匿名流量同样被约束。</li>
 *   <li><b>并发槽位必释放</b>：同步请求走 {@code afterCompletion}，
 *       异步请求走 {@code afterConcurrentHandlingStarted}，两者都带幂等标记防止重复释放。</li>
 *   <li><b>未通过鉴权的请求不进入本拦截器</b>：Spring Security 的过滤器链在
 *       DispatcherServlet 更外层，受保护端点上的「无令牌请求」会被鉴权层直接拒绝（401），
 *       根本走不到这里。这是刻意的取舍 —— 这类请求在鉴权层就被廉价地挡掉了，
 *       不需要再消耗限流计数；它带来两个必须知道的结论：
 *       <ul>
 *         <li>受保护端点的「未登录洪水」不由本模块负责，属于网关 / LB 层全局限流的职责；</li>
 *         <li>白名单端点（登录、验证码、商品、首页、CMS、秒杀浏览、支付回调）会被正常计数，
 *             而它们恰好就是匿名刷量真正需要被约束的地方。</li>
 *       </ul>
 *       该边界由 {@code RateLimitIntegrationTest#unauthenticatedRequestsAreRejectedBeforeRateLimiting}
 *       固化，避免将来有人误以为「所有请求都过限流」而做出错误的安全推断。</li>
 *   <li><b>LOCAL 配额按实例数切分</b>：LOCAL 层计数在进程内，N 个实例的总放行量
 *       会被放大 N 倍。这里按 {@code mall.cluster.instance-count} 把额度切成
 *       {@code ceil(limit / N)}（且至少保留 1），使全局总量回到配置值附近；
 *       DISTRIBUTED 层本身全局准确，不切分。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements AsyncHandlerInterceptor {

    private static final String KEY_PREFIX = "mall:rate:";
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    /** 并发槽位 key 的请求属性名。 */
    private static final String ATTR_CONCURRENT_KEY = RateLimitInterceptor.class.getName() + ".concurrentKey";
    /** 已释放标记，避免异步场景下被释放两次。 */
    private static final String ATTR_RELEASED = RateLimitInterceptor.class.getName() + ".released";

    private final RateLimitProperties properties;
    private final RateLimitPolicies policies;
    private final LocalRateLimiter localRateLimiter;
    private final DistributedRateLimiter distributedRateLimiter;
    /** 集群实例数：用于把 LOCAL 层（进程内）配额切分到各实例。 */
    private final ClusterProperties clusterProperties;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        // 边界 1：异步 / 错误二次分发、FORWARD 内部转发都不重复计数
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        // 边界 2：跨域预检不计数
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String path = requestPath(request);
        // 边界 3：运维 / 文档 / 静态资源豁免
        if (policies.isExempt(path)) {
            return true;
        }
        // 边界 4：非控制器处理器（静态资源等）
        if (!(handler instanceof org.springframework.web.method.HandlerMethod)) {
            return true;
        }

        RateLimitPolicy policy = policies.resolve(request.getMethod(), path);
        String dimension = resolveDimension(policy.dimension(), request);
        String key = KEY_PREFIX + policy.name() + ":" + dimension;

        // LOCAL 层是进程内计数：多实例部署时按实例数切分额度，否则总放行量会被放大 N 倍。
        // DISTRIBUTED 层本身全局准确，effectiveLimit 会原样返回配置值。
        int instanceCount = clusterProperties.getInstanceCount();
        int limit = policy.effectiveLimit(instanceCount);

        // ---- 频率限制 ----
        RateLimitResult result = policy.tier() == RateLimitTier.LOCAL
                ? localRateLimiter.tryAcquire(key, limit, policy.windowSeconds())
                : distributedRateLimiter.tryAcquire(key, limit, policy.windowSeconds(), policy.fallback());

        if (!result.allowed()) {
            log.warn("[限流] 规则={}, 维度={}, 阈值={}/{}s, 层级={}",
                    policy.name(), dimension, policy.limit(), policy.windowSeconds(), policy.tier());
            throw new RateLimitException("系统繁忙，请稍后再试", result.retryAfterSeconds());
        }

        // ---- 并发度限制（可选）----
        // 在途并发同样是进程内计数，按实例数切分
        int maxConcurrent = policy.effectiveMaxConcurrent(instanceCount);
        if (maxConcurrent > 0) {
            String concurrentKey = key + ":conc";
            if (!localRateLimiter.tryAcquireConcurrent(concurrentKey, maxConcurrent)) {
                log.warn("[限流] 规则={} 并发已达上限 {}（实例数 {}），拒绝请求",
                        policy.name(), maxConcurrent, instanceCount);
                throw new RateLimitException("当前请求过多，请稍后再试", 1);
            }
            request.setAttribute(ATTR_CONCURRENT_KEY, concurrentKey);
        }

        response.setHeader(HEADER_LIMIT, String.valueOf(result.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.remaining()));
        return true;
    }

    /** 同步请求结束时释放并发槽位。 */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        releaseConcurrency(request);
    }

    /** 异步请求（DeferredResult）在初次分发结束时走这里，同样要释放，否则槽位会泄漏。 */
    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        releaseConcurrency(request);
    }

    private void releaseConcurrency(HttpServletRequest request) {
        Object key = request.getAttribute(ATTR_CONCURRENT_KEY);
        if (key == null || request.getAttribute(ATTR_RELEASED) != null) {
            return;
        }
        request.setAttribute(ATTR_RELEASED, Boolean.TRUE);
        localRateLimiter.releaseConcurrent(key.toString());
    }

    /**
     * 解析限流维度标识。
     *
     * <p>SUBJECT 先取买家 userId，再取商家 merchantUserId，都取不到才回退 IP。</p>
     */
    private String resolveDimension(RateLimitDimension dimension, HttpServletRequest request) {
        return switch (dimension) {
            case GLOBAL -> "global";
            case IP -> "ip:" + clientIp(request);
            case SUBJECT -> {
                Long buyerId = SecurityUtils.getUserIdOrNull();
                if (buyerId != null) {
                    yield "u:" + buyerId;
                }
                Long merchantUserId = MerchantSecurityUtils.getMerchantUserIdOrNull();
                yield merchantUserId != null ? "m:" + merchantUserId : "ip:" + clientIp(request);
            }
        };
    }

    /**
     * 取应用内路径（去掉 contextPath）。
     *
     * <p>优先使用 Spring MVC 暴露的 {@code BEST_MATCHING_PATTERN_ATTRIBUTE} ——
     * 它是模板化的（如 {@code /api/v1/products/{id}}），比含真实 ID 的 URI 更适合做日志与诊断；
     * 但限流判定要用真实路径去匹配 Ant 模式，所以这里返回真实路径。</p>
     */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 从代理头中取真实客户端 IP（限流按 IP 时不能取到网关 IP）。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /** 供诊断：当前请求命中的路由模板（可能为 null）。 */
    static String matchedPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? null : pattern.toString();
    }
}
