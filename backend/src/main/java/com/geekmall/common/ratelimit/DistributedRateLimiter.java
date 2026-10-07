package com.geekmall.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 跨实例限流执行器：Redis 固定窗口计数。
 *
 * <p><b>为什么用 Lua</b>：朴素的 {@code INCR} + {@code EXPIRE} 两步之间如果进程退出、
 * 或第一次自增后紧接着网络抖动，key 就会永久没有过期时间，计数器只增不减 ——
 * 表现为「这个接口在某天之后对某个用户永久 429」，是线上很难排查的一类事故。
 * 放进一个脚本里由 Redis 原子执行，就能保证「自增」与「设置过期」要么都发生、要么都不发生。</p>
 *
 * <p><b>故障降级</b>：Redis 不可用时按策略表的 {@link RateLimitFallback} 决定放行还是拒绝，
 * 具体取舍见该枚举的说明。降级一定会打 error 日志，因为限流器失效本身就是需要立刻介入的故障。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedRateLimiter {

    /**
     * 原子自增并保证首次自增时设置过期。
     *
     * <p>KEYS[1] 计数 key，ARGV[1] 窗口秒数；返回自增后的计数。</p>
     */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /** 避免因窗口边界对齐问题产生永不结束的 TTL。 */
    private static final long TTL_GRACE_SECONDS = 1L;

    private final StringRedisTemplate redisTemplate;

    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds, RateLimitFallback fallback) {
        try {
            Long count = redisTemplate.execute(INCR_WITH_TTL, List.of(key),
                    String.valueOf(windowSeconds + TTL_GRACE_SECONDS));
            if (count == null) {
                return degrade(key, limit, fallback, "Redis 未返回计数结果");
            }
            if (count > limit) {
                Long ttl = redisTemplate.getExpire(key);
                long retryAfter = ttl == null || ttl <= 0 ? windowSeconds : ttl;
                return RateLimitResult.reject(limit, retryAfter);
            }
            return RateLimitResult.allow(limit, Math.max(0, limit - count));
        } catch (Exception e) {
            return degrade(key, limit, fallback, e.getMessage());
        }
    }

    private RateLimitResult degrade(String key, int limit, RateLimitFallback fallback, String cause) {
        log.error("分布式限流不可用，按 {} 降级：key={}, cause={}", fallback, key, cause);
        return fallback == RateLimitFallback.FAIL_CLOSED
                ? RateLimitResult.reject(limit, 1)
                : RateLimitResult.allow(limit, limit);
    }
}
