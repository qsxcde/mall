package com.geekmall.common.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 限流策略注册表 —— <b>全站限流规则的唯一事实来源</b>。
 *
 * <p>设计意图（对应「不允许遗漏、不允许重复」这个要求）：</p>
 * <ol>
 *   <li><b>集中</b>：所有规则都在这里的 {@link #RULES} 表中，一份文件即可完整审阅，
 *       不用翻 117 个方法去看哪个漏了注解。</li>
 *   <li><b>顺序敏感</b>：自上而下「首个匹配即生效」，因此规则按「具体 → 笼统」排列，
 *       例如 {@code /api/v1/merchant/auth/login} 必须排在 {@code /api/v1/merchant/**} 之前。</li>
 *   <li><b>可证明无遗漏</b>：{@link #DEFAULT} 是兜底规则，保证「不存在无限流的端点」；
 *       同时 {@code RateLimitCoverageIntegrationTest} 会遍历所有已注册端点，
 *       凡是只落到兜底规则的端点即视为「未分类」并让测试失败 —— 即新增接口必须显式分类。</li>
 *   <li><b>可证明无重复</b>：类加载时校验规则名唯一，测试再校验「方法 + 路径模式」不重复。</li>
 * </ol>
 *
 * <p>层级分配原则见 {@link RateLimitTier}：只有「限流是唯一防线」的接口才付 Redis 往返的成本。</p>
 */
@Component
public class RateLimitPolicies {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 任意 HTTP 方法。 */
    private static final Set<HttpMethod> ANY = null;
    /** 读方法。把 HEAD 一并纳入 —— Spring 会把 HEAD 映射到 GET 处理器。 */
    private static final Set<HttpMethod> READ = Set.of(HttpMethod.GET, HttpMethod.HEAD);
    /** 写方法。 */
    private static final Set<HttpMethod> WRITE = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
            HttpMethod.PATCH);
    /** 仅 POST。 */
    private static final Set<HttpMethod> POST = Set.of(HttpMethod.POST);
    /** 「我的评价」同时承载查询与删除，故单列一个方法集合。 */
    private static final Set<HttpMethod> READ_OR_DELETE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE);

    /**
     * 豁免路径：运维、文档与静态资源。
     *
     * <p>它们要么是健康检查/监控抓取（限流会直接导致误判故障），
     * 要么是静态文件（不消耗后端计算资源），要么本身就是错误页。</p>
     */
    private static final List<String> EXEMPT = List.of(
            "/actuator/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            // springdoc 还暴露 .yaml 变体，且它是单段路径，不会被 /v3/api-docs/** 覆盖
            "/v3/api-docs.yaml",
            "/v3/api-docs/**",
            "/doc.html",
            "/webjars/**",
            "/uploads/**",
            "/favicon.ico",
            "/error");

    /**
     * 兜底规则：未被任何显式规则匹配到的请求按 IP 限 100 次/秒。
     *
     * <p>存在的意义是「安全网」—— 哪怕将来有人加了新接口忘了分类，
     * 也不会出现完全裸奔的端点。覆盖度测试会把落到这里的端点判为「未分类」，
     * 因此兜底规则只用于线上保底，不能当作分类的替代品。</p>
     */
    public static final RateLimitPolicy DEFAULT = new RateLimitPolicy(
            "default", ANY, List.of("/**"), RateLimitDimension.IP, 100, 1, 0,
            RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN);

    private static final List<RateLimitPolicy> RULES = List.of(

            /* ============ 1. 认证：撞库 / 短信轰炸 / 刷号 的主要防线 ============ */
            // 这些接口还没有登录态，只能按 IP 限；一旦限流失效就等于把刷接口的口子敞开，
            // 因此用 DISTRIBUTED（多实例下全局准确）+ FAIL_CLOSED（Redis 挂了宁可拒绝）。
            rate("auth-login", POST, RateLimitDimension.IP, 10, 60, RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED,
                    "/api/v1/auth/login"),
            rate("auth-sms-login", POST, RateLimitDimension.IP, 10, 60, RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED,
                    "/api/v1/auth/login/sms"),
            // 短信有真实成本，阈值比登录更紧
            rate("auth-sms-code", POST, RateLimitDimension.IP, 5, 60, RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED,
                    "/api/v1/auth/sms-code"),
            rate("auth-register", POST, RateLimitDimension.IP, 5, 60, RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED,
                    "/api/v1/auth/register"),
            rate("auth-reset", POST, RateLimitDimension.IP, 5, 60, RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED,
                    "/api/v1/auth/password/reset"),
            // 登出是纯本地状态清理，成本低，给宽松额度
            rate("auth-logout", POST, RateLimitDimension.SUBJECT, 10, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/auth/logout"),

            /* ============ 2. 商家登录（同样属于防撞库；必须排在 /merchant/** 之前） ============ */
            rate("merchant-login", POST, RateLimitDimension.IP, 10, 60, RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED,
                    "/api/v1/merchant/auth/login"),
            rate("merchant-logout", POST, RateLimitDimension.SUBJECT, 10, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/merchant/auth/logout"),

            /* ============ 3. 文件上传：单次成本最高（IO + 对象存储），频率与并发双限 ============ */
            concurrent("file-upload", POST, RateLimitDimension.SUBJECT, 10, 60, 5,
                    RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED, "/api/v1/files"),

            /* ============ 4. 支付 ============ */
            // 渠道回调由第三方发起，额度必须放宽，否则正常支付会被自己的限流挡掉
            rate("pay-callback", POST, RateLimitDimension.IP, 200, 1, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/pay/callback"),
            rate("pay-mock", POST, RateLimitDimension.SUBJECT, 10, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/pay/*/mock-pay"),
            rate("pay-create", POST, RateLimitDimension.SUBJECT, 10, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/pay/create"),
            // 收银台前端会轮询支付状态，额度要给足
            rate("pay-status", READ, RateLimitDimension.SUBJECT, 60, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/pay/**"),

            /* ============ 5. 秒杀 ============ */
            // 抢购的真正护栏是 Redis Lua 预扣 + 一人一单，这里的限流只是挡住脚本高频重放；
            // 用 LOCAL 避免在超热点路径上再加一次 Redis 往返。
            rate("seckill-grab", POST, RateLimitDimension.SUBJECT, 5, 1, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/seckill/*/order"),
            // 削峰模式下前端会高频轮询抢购结果，额度按「1 秒 1 次」的轮询节奏放宽。
            // 必须排在下面 /api/v1/seckill/** 之前 —— 规则表是「首个匹配即生效」
            rate("seckill-result", READ, RateLimitDimension.SUBJECT, 120, 10, RateLimitTier.LOCAL,
                    RateLimitFallback.FAIL_OPEN, "/api/v1/seckill/result/*"),
            rate("seckill-read", READ, RateLimitDimension.IP, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/seckill/**"),

            /* ============ 6. 交易 ============ */
            // 下单：防重复提交由 requestId 幂等键 + uk_user_request 唯一索引兜底，
            // 因此限流只为挡手抖与脚本，LOCAL 足够；并发上限用于防止慢事务堆积。
            concurrent("trade-submit", POST, RateLimitDimension.SUBJECT, 5, 1, 10,
                    RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN, "/api/v1/trade/orders"),
            // 联调用的模拟发货接口（mall.mock.enabled=true 才注册），与真实交易分开计量
            rate("trade-mock", POST, RateLimitDimension.SUBJECT, 20, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/trade/mock/**"),
            rate("trade-preorder", POST, RateLimitDimension.SUBJECT, 10, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/trade/pre-order"),
            rate("trade-action", POST, RateLimitDimension.SUBJECT, 10, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/trade/orders/**"),

            /* ============ 7. 营销：领券 / 积分兑换（唯一索引兜底，限本地） ============ */
            rate("coupon-claim", POST, RateLimitDimension.SUBJECT, 5, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/coupons/claim/**"),
            rate("coupon-read", READ, RateLimitDimension.IP, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/coupons/**"),
            rate("points-exchange", POST, RateLimitDimension.SUBJECT, 5, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/points/exchange/**"),
            rate("points-read", READ, RateLimitDimension.SUBJECT, 30, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/points/**"),

            /* ============ 8. 评价 / 售后 ============ */
            rate("review-write", POST, RateLimitDimension.SUBJECT, 10, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/reviews"),
            // 「我的评价」必须排在 /api/v1/user/** 之前
            rate("review-mine", READ_OR_DELETE, RateLimitDimension.SUBJECT, 20, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/user/reviews/**"),
            rate("aftersale-read", READ, RateLimitDimension.SUBJECT, 30, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/aftersales/**"),
            rate("aftersale-write", POST, RateLimitDimension.SUBJECT, 10, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/aftersales/**"),

            /* ============ 9. 订单 / 购物车 / 用户中心 / 会员 / 消息 ============ */
            rate("order-read", READ, RateLimitDimension.SUBJECT, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/orders/**"),
            rate("cart-read", READ, RateLimitDimension.SUBJECT, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/cart/**"),
            rate("cart-write", WRITE, RateLimitDimension.SUBJECT, 20, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/cart/**"),
            rate("user-read", READ, RateLimitDimension.SUBJECT, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/user/**"),
            rate("user-write", WRITE, RateLimitDimension.SUBJECT, 20, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/user/**"),
            rate("member-read", READ, RateLimitDimension.SUBJECT, 30, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/member/**"),
            rate("message-read", READ, RateLimitDimension.SUBJECT, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/messages/**"),
            rate("message-write", POST, RateLimitDimension.SUBJECT, 30, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/messages/**"),

            /* ============ 10. 商家后台 ============ */
            // 提现涉及资金，单独收紧（必须排在 merchant-write 之前）
            rate("merchant-withdraw", POST, RateLimitDimension.SUBJECT, 3, 60, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/merchant/fund/withdraw"),
            // 看板/结算聚合查询本身较重，限流同时保护数据库
            rate("merchant-read", READ, RateLimitDimension.SUBJECT, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/merchant/**"),
            rate("merchant-write", WRITE, RateLimitDimension.SUBJECT, 20, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/merchant/**"),

            /* ============ 11. 公开读接口（匿名按 IP；商品域是最大读热点） ============ */
            rate("home-read", READ, RateLimitDimension.IP, 200, 1, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/home/**"),
            rate("category-read", READ, RateLimitDimension.IP, 200, 1, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/categories/**"),
            rate("product-read", READ, RateLimitDimension.IP, 200, 1, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/products/**"),
            rate("content-read", READ, RateLimitDimension.IP, 60, 10, RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN,
                    "/api/v1/cms/**"));

    static {
        // 规则名必须唯一：它是 Redis key 的一部分，重名会导致不同接口互相挤占额度
        Set<String> names = new HashSet<>();
        for (RateLimitPolicy policy : RULES) {
            if (!names.add(policy.name())) {
                throw new IllegalStateException("限流规则名重复：" + policy.name());
            }
        }
    }

    private static RateLimitPolicy rate(String name, Set<HttpMethod> methods, RateLimitDimension dimension,
                                        int limit, int windowSeconds, RateLimitTier tier,
                                        RateLimitFallback fallback, String... patterns) {
        return new RateLimitPolicy(name, methods, List.of(patterns), dimension, limit, windowSeconds, 0, tier, fallback);
    }

    private static RateLimitPolicy concurrent(String name, Set<HttpMethod> methods, RateLimitDimension dimension,
                                              int limit, int windowSeconds, int maxConcurrent,
                                              RateLimitTier tier, RateLimitFallback fallback, String... patterns) {
        return new RateLimitPolicy(name, methods, List.of(patterns), dimension, limit, windowSeconds,
                maxConcurrent, tier, fallback);
    }

    /** 解析请求命中的规则；没有显式规则时返回 {@link #DEFAULT}。 */
    public RateLimitPolicy resolve(String httpMethod, String path) {
        if (path == null || path.isEmpty()) {
            return DEFAULT;
        }
        for (RateLimitPolicy policy : RULES) {
            if (!policy.supports(httpMethod)) {
                continue;
            }
            for (String pattern : policy.patterns()) {
                if (MATCHER.match(pattern, path)) {
                    return policy;
                }
            }
        }
        return DEFAULT;
    }

    /** 运维 / 文档 / 静态资源路径不参与限流。 */
    public boolean isExempt(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        for (String pattern : EXEMPT) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 全部显式规则（顺序即匹配优先级），供文档与测试使用。 */
    public static List<RateLimitPolicy> rules() {
        return RULES;
    }

    /** 豁免路径模式，供文档与测试使用。 */
    public static List<String> exemptPatterns() {
        return EXEMPT;
    }
}
