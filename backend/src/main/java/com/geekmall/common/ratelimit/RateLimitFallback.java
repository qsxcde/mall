package com.geekmall.common.ratelimit;

/**
 * 限流组件自身故障（仅 {@link RateLimitTier#DISTRIBUTED} 会触发，即 Redis 不可用）时的降级策略。
 *
 * <p>这是个必须显式做出的选择，不能一边倒地 fail-open 或 fail-closed：</p>
 * <ul>
 *   <li>{@link #FAIL_OPEN}：放行请求。适用于「限流只是锦上添花」的接口 ——
 *       限流器挂了不应该把正常业务一起拖死。</li>
 *   <li>{@link #FAIL_CLOSED}：拒绝请求。适用于「限流是唯一防线」的接口 ——
 *       登录、验证码、改密这类接口如果失去限流，等于把撞库/短信轰炸的口子完全敞开，
 *       此时宁可短暂不可用（Redis 故障本身就是需要立刻处理的故障）。</li>
 * </ul>
 */
public enum RateLimitFallback {

    /** 故障时放行（默认），避免限流组件成为新的故障点。 */
    FAIL_OPEN,

    /** 故障时拒绝，优先保证防刷能力不失效。 */
    FAIL_CLOSED
}
