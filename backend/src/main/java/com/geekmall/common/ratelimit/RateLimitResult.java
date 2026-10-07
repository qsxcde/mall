package com.geekmall.common.ratelimit;

/**
 * 一次限流判定结果。
 *
 * @param allowed           是否放行
 * @param limit             本规则的窗口上限（回显给前端，便于其自适应退避）
 * @param remaining         当前窗口剩余可用次数
 * @param retryAfterSeconds 被拒时建议的重试等待秒数（写入 {@code Retry-After} 响应头）
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterSeconds) {

    public static RateLimitResult allow(long limit, long remaining) {
        return new RateLimitResult(true, limit, Math.max(0, remaining), 0);
    }

    public static RateLimitResult reject(long limit, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, 0, Math.max(1, retryAfterSeconds));
    }
}
