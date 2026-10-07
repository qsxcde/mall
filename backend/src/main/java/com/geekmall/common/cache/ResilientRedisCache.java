package com.geekmall.common.cache;

import com.geekmall.common.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

/**
 * 「抗打」缓存：在 Spring Data Redis 的 {@link RedisCache} 之上补齐缓存三防。
 *
 * <table border="1">
 *   <caption>与本类相关的三防职责</caption>
 *   <tr><th>问题</th><th>本类做的工作</th><th>配套</th></tr>
 *   <tr>
 *     <td>穿透</td>
 *     <td>Redis 故障时降级不阻断；空值写入由 {@code TtlFunction} 用短 TTL 承接</td>
 *     <td>{@link RedisBloomFilter} 挡「一定不存在」的 id</td>
 *   </tr>
 *   <tr>
 *     <td>击穿</td>
 *     <td><b>跨实例互斥重建</b>：热点 key 失效时只有一个节点回源，其余等待复用</td>
 *     <td>—</td>
 *   </tr>
 *   <tr>
 *     <td>雪崩</td>
 *     <td>本地 L1 承接瞬时流量；Redis 故障时熔断降级不排队</td>
 *     <td>{@link JitterTtlFunctionFactory} 打散 TTL</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么必须自己实现击穿防护</b>：Spring Data Redis 的 {@code @Cacheable(sync = true)}
 * 只在<b>单个 JVM 内</b>用 {@link java.util.concurrent.locks.ReentrantLock} 串行化
 * （见 {@code RedisCache#getSynchronized}）。多实例部署时，N 个节点会在同一瞬间各自回源一次，
 * 热点 key 失效造成的数据库冲击与没加锁几乎一样。这里换成 Redis 互斥锁才能真正解决。</p>
 *
 * <p><b>可用性优先原则</b>：所有 Redis 异常都被吞掉并记录指标，绝不让「缓存故障」升级为
 * 「业务不可用」。未抢到锁的线程也不会无限等待，超时后放行回源 —— 宁可多查几次库，
 * 也不能让请求被锁拖到超时。</p>
 */
@Slf4j
public class ResilientRedisCache extends RedisCache {

    /** 释放锁必须校验持有者，避免误删他人锁（A 超时后 B 拿到锁，A 再删就会删掉 B 的）。 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final CacheProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final CacheGovernanceMetrics metrics;
    private final CacheCircuitBreaker breaker;

    /** 本地一级缓存；未启用时为 {@code null}。 */
    private final com.github.benmanes.caffeine.cache.Cache<String, Object> localCache;

    protected ResilientRedisCache(String name,
                                  RedisCacheWriter cacheWriter,
                                  RedisCacheConfiguration cacheConfiguration,
                                  CacheProperties properties,
                                  StringRedisTemplate redisTemplate,
                                  CacheGovernanceMetrics metrics) {
        super(name, cacheWriter, cacheConfiguration);
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.breaker = properties.getBreaker().isEnabled()
                ? new CacheCircuitBreaker(properties.getBreaker().getFailureThreshold(),
                properties.getBreaker().getOpenDuration())
                : null;
        this.localCache = initLocalCache(name);
    }

    private com.github.benmanes.caffeine.cache.Cache<String, Object> initLocalCache(String name) {
        CacheProperties.Local local = properties.getLocal();
        if (!local.isEnabled() || local.getNames() == null || !local.getNames().contains(name)) {
            return null;
        }
        return com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(local.getMaxSize())
                .expireAfterWrite(local.getTtl())
                .build();
    }

    /* ------------------------------ 读路径 ------------------------------ */

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper local = localGet(key);
        if (local != null) {
            metrics.recordHit(getName(), "local");
            return local;
        }
        ValueWrapper wrapper = readRedis(key);
        if (wrapper != null) {
            metrics.recordHit(getName(), "redis");
            writeLocal(key, wrapper.get());
        }
        return wrapper;
    }

    /**
     * 带重建的读取（{@code @Cacheable(sync = true)} 走这里）。
     *
     * <p>流程：L1 → Redis → 抢分布式锁重建 → 未抢到则等待 → 超时兜底放行。</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
        ValueWrapper local = localGet(key);
        if (local != null) {
            metrics.recordHit(getName(), "local");
            return (T) local.get();
        }

        ValueWrapper wrapper = readRedis(key);
        if (wrapper != null) {
            metrics.recordHit(getName(), "redis");
            writeLocal(key, wrapper.get());
            return (T) wrapper.get();
        }

        if (!properties.getLock().isEnabled()) {
            // 关闭击穿防护：退化为 Spring 默认行为（进程内锁）
            return loadAndCache(key, valueLoader);
        }
        return rebuildWithLock(key, valueLoader);
    }

    /**
     * 读取 Redis，并把 Redis 异常统一转成「未命中 + 熔断计数」。
     *
     * <p>返回 {@code null} 仅表示「Redis 未命中或不可用」，此时调用方应回源；
     * 空值缓存命中会返回 {@code SimpleValueWrapper(null)}（非 null），两者语义不同。</p>
     */
    private ValueWrapper readRedis(Object key) {
        if (breaker != null && !breaker.allowRequest()) {
            metrics.recordBreakerOpen(getName());
            return null;
        }
        try {
            ValueWrapper wrapper = super.get(key);
            if (breaker != null) {
                breaker.recordSuccess();
            }
            return wrapper;
        } catch (DataAccessException ex) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            metrics.recordRedisError(getName(), ex);
            log.warn("读取缓存失败，本次按未命中处理：cache={}, error={}", getName(), ex.getMessage());
            return null;
        }
    }

    /* ------------------------------ 击穿防护 ------------------------------ */

    @SuppressWarnings("unchecked")
    private <T> T rebuildWithLock(Object key, java.util.concurrent.Callable<T> valueLoader) {
        String lockKey = RedisKeys.CACHE_LOCK + getName() + ":" + createCacheKey(key);
        String token = UUID.randomUUID().toString();
        boolean locked = tryLock(lockKey, token);
        if (locked) {
            try {
                // 双重检查：等锁期间可能已被其他节点重建完成
                ValueWrapper again = readRedis(key);
                if (again != null) {
                    writeLocal(key, again.get());
                    return (T) again.get();
                }
                metrics.recordRebuild(getName());
                return loadAndCache(key, valueLoader);
            } finally {
                unlock(lockKey, token);
            }
        }
        metrics.recordLockWait(getName());
        return waitForRebuild(key, valueLoader);
    }

    private boolean tryLock(String lockKey, String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, properties.getLock().getTtl()));
        } catch (DataAccessException ex) {
            metrics.recordRedisError(getName(), ex);
            return false;
        }
    }

    private void unlock(String lockKey, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (DataAccessException ex) {
            // 锁会随 TTL 自动过期，这里只记录，不打断业务
            log.debug("释放缓存重建锁失败（将由 TTL 兜底）：{}", ex.getMessage());
        }
    }

    /**
     * 未抢到锁：轮询等待重建结果。
     *
     * <p>等待超过 {@code waitTimeout} 就直接回源且不写缓存 —— 这是刻意的可用性取舍：
     * 锁的语义是「尽量减少并发回源」，而不是「宁可让用户超时也不许回源」。</p>
     */
    @SuppressWarnings("unchecked")
    private <T> T waitForRebuild(Object key, java.util.concurrent.Callable<T> valueLoader) {
        long deadline = System.nanoTime() + properties.getLock().getWaitTimeout().toNanos();
        long intervalMillis = Math.max(1, properties.getLock().getRetryInterval().toMillis());
        while (System.nanoTime() < deadline) {
            sleep(intervalMillis);
            ValueWrapper local = localGet(key);
            if (local != null) {
                return (T) local.get();
            }
            ValueWrapper wrapper = readRedis(key);
            if (wrapper != null) {
                writeLocal(key, wrapper.get());
                return (T) wrapper.get();
            }
        }
        metrics.recordLockTimeout(getName());
        return callLoader(key, valueLoader);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待缓存重建时被中断", ex);
        }
    }

    /* ------------------------------ 写路径 ------------------------------ */

    @Override
    public void put(Object key, Object value) {
        try {
            super.put(key, value);
        } catch (DataAccessException ex) {
            // 写缓存失败不应让业务失败：记录后继续，由下次请求重新回源
            metrics.recordRedisError(getName(), ex);
            log.warn("写入缓存失败：cache={}, error={}", getName(), ex.getMessage());
        }
        writeLocal(key, value);
    }

    @Override
    public void evict(Object key) {
        try {
            super.evict(key);
        } catch (DataAccessException ex) {
            metrics.recordRedisError(getName(), ex);
        }
        if (localCache != null) {
            localCache.invalidate(createCacheKey(key));
        }
    }

    /**
     * 清空缓存。
     *
     * <p>注意：只能清掉<b>本实例</b>的 L1，其他实例的 L1 会继续服务至多
     * {@code mall.cache.local.ttl}（默认 3s）。这正是把 L1 白名单限制在
     * 「能容忍数秒陈旧」的聚合类数据上的原因。</p>
     */
    @Override
    public void clear() {
        try {
            super.clear();
        } catch (DataAccessException ex) {
            metrics.recordRedisError(getName(), ex);
        }
        if (localCache != null) {
            localCache.invalidateAll();
        }
    }

    /* ------------------------------ 本地 L1 ------------------------------ */

    private ValueWrapper localGet(Object key) {
        if (localCache == null) {
            return null;
        }
        Object value = localCache.getIfPresent(createCacheKey(key));
        if (value == null) {
            return null;
        }
        // NullValue 是空值缓存的哨兵，还原为真正的 null 返回，与 Redis 路径语义保持一致
        return new SimpleValueWrapper(value instanceof NullValue ? null : value);
    }

    private void writeLocal(Object key, Object value) {
        if (localCache == null) {
            return;
        }
        // Caffeine 不接受 null value，用 Spring 的 NullValue 哨兵表示「已确认不存在」
        localCache.put(createCacheKey(key), value == null ? NullValue.INSTANCE : value);
    }

    /* ------------------------------ 回源 ------------------------------ */

    private <T> T loadAndCache(Object key, java.util.concurrent.Callable<T> valueLoader) {
        T value = callLoader(key, valueLoader);
        put(key, value);
        return value;
    }

    private <T> T callLoader(Object key, java.util.concurrent.Callable<T> valueLoader) {
        try {
            return valueLoader.call();
        } catch (Exception ex) {
            throw new Cache.ValueRetrievalException(key, valueLoader, ex);
        }
    }

    /** 供测试判断熔断状态。 */
    CacheCircuitBreaker getBreaker() {
        return breaker;
    }

    /** 本缓存的本地 L1 是否启用（白名单决定），供测试与运维诊断。 */
    public boolean isLocalCacheEnabled() {
        return localCache != null;
    }

    /** L1 当前条目数（近似值）。 */
    public long localCacheSize() {
        return localCache == null ? 0L : localCache.estimatedSize();
    }
}
