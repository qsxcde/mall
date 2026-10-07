package com.geekmall.common.ratelimit;

import lombok.Getter;

/**
 * 触发限流时抛出，由全局异常处理器转为 HTTP 429。
 *
 * <p>携带 {@code retryAfterSeconds}，最终写入 {@code Retry-After} 响应头，
 * 让客户端（以及压测工具）知道该退避多久，而不是立刻重试把窗口继续打满。</p>
 */
@Getter
public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
