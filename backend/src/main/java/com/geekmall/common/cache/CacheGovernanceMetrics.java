package com.geekmall.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 缓存治理指标：把「三防」是否真的生效变成可观测的数字。
 *
 * <p>没有指标时，「加了缓存三防」只是一句声明；有了指标才能在故障复盘时回答
 * 「当时到底是空值缓存挡住的，还是布隆过滤器挡住的，还是熔断降级了」。</p>
 *
 * <ul>
 *   <li>{@code mall_cache_hits_total}：命中总数（含本地 L1 与 Redis，按 level 区分）</li>
 *   <li>{@code mall_cache_rebuild_total}：回源重建次数（该值应远小于请求数，否则说明防击穿失效）</li>
 *   <li>{@code mall_cache_lock_wait_total}：未抢到锁而等待的次数（击穿防护正在起作用）</li>
 *   <li>{@code mall_cache_lock_timeout_total}：等待超时后放行回源的次数</li>
 *   <li>{@code mall_cache_breaker_open_total}：因熔断跳过缓存的次数</li>
 *   <li>{@code mall_cache_redis_error_total}：Redis 访问异常次数</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CacheGovernanceMetrics {

    private final MeterRegistry meterRegistry;

    public void recordHit(String cacheName, String level) {
        counter("mall_cache_hits_total", cacheName, level).increment();
    }

    public void recordRebuild(String cacheName) {
        meterRegistry.counter("mall_cache_rebuild_total", "cache", cacheName).increment();
    }

    public void recordLockWait(String cacheName) {
        meterRegistry.counter("mall_cache_lock_wait_total", "cache", cacheName).increment();
    }

    public void recordLockTimeout(String cacheName) {
        meterRegistry.counter("mall_cache_lock_timeout_total", "cache", cacheName).increment();
    }

    public void recordBreakerOpen(String cacheName) {
        meterRegistry.counter("mall_cache_breaker_open_total", "cache", cacheName).increment();
    }

    public void recordRedisError(String cacheName, Exception ex) {
        meterRegistry.counter("mall_cache_redis_error_total",
                "cache", cacheName,
                "exception", ex.getClass().getSimpleName()).increment();
    }

    private Counter counter(String name, String cacheName, String level) {
        return meterRegistry.counter(name, "cache", cacheName, "level", level);
    }
}
