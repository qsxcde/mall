package com.geekmall.common.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生成带抖动的 TTL 策略，同时承担「空值短 TTL」。
 *
 * <p>Spring Data Redis 从 2.4 起允许用 {@link RedisCacheWriter.TtlFunction} 动态决定每条
 * 缓存的过期时间，且回调里拿到的是 {@code @Cacheable} 方法的<b>原始返回值</b>
 * （为 {@code null} 时即代表这是一次「空值缓存」）。因此在这里一个函数就能同时解决
 * 雪崩（抖动）与穿透（空值短 TTL），无需子类化 {@code RedisCache} 去碰私有字段。</p>
 */
@Component
@RequiredArgsConstructor
public class JitterTtlFunctionFactory {

    private final CacheProperties properties;

    /**
     * 为某个缓存生成 TTL 策略。
     *
     * @param cacheName 缓存名，用于取基础 TTL
     * @return 空值返回 {@code nullTtl}；其余值返回 {@code baseTtl ± jitter}
     */
    public RedisCacheWriter.TtlFunction create(String cacheName) {
        Duration baseTtl = properties.getTtl().getOrDefault(cacheName, properties.getDefaultTtl());
        Duration nullTtl = properties.getNullTtl();
        double ratio = properties.getJitterRatio();
        return (key, value) -> {
            if (value == null) {
                return nullTtl;
            }
            return jitter(baseTtl, ratio);
        };
    }

    /** 在基础 TTL 上叠加 {@code ±ratio} 的随机浮动，把集中失效打散。 */
    public static Duration jitter(Duration baseTtl, double ratio) {
        long baseMillis = baseTtl.toMillis();
        if (ratio <= 0 || baseMillis <= 0) {
            return baseTtl;
        }
        long bound = (long) (baseMillis * ratio);
        if (bound <= 0) {
            return baseTtl;
        }
        long delta = ThreadLocalRandom.current().nextLong(-bound, bound + 1);
        long ttl = Math.max(1, baseMillis + delta);
        return Duration.ofMillis(ttl);
    }
}
