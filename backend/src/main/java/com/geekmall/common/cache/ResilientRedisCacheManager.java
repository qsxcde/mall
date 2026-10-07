package com.geekmall.common.cache;

import com.geekmall.common.resilience.ResilienceGuard;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * 把每个缓存都创建为 {@link ResilientRedisCache}。
 *
 * <p>Spring Data Redis 的 {@code RedisCacheManager} 把所有 Cache 实例的创建收敛到
 * {@link #createRedisCache(String, RedisCacheConfiguration)} 这一个受保护方法，
 * 因此只需覆写它，就能让全站缓存同时获得三防能力，业务代码零改动。</p>
 */
public class ResilientRedisCacheManager extends RedisCacheManager {

    /**
     * 缓存层共用的熔断资源名。
     *
     * <p>不给每个 cache 单独建熔断器，是因为它们背后是<b>同一个 Redis</b>：
     * Redis 整体不可用时所有缓存同时不可用，分开计数只会让每个缓存都要各自
     * 攒够最小样本数才熔断，反而拖慢熔断速度。共用一份状态才是正确粒度。</p>
     */
    public static final String CACHE_RESOURCE = "cache";

    private final CacheProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final CacheGovernanceMetrics metrics;
    private final ResilienceGuard resilienceGuard;

    public ResilientRedisCacheManager(RedisCacheWriter cacheWriter,
                                      RedisCacheConfiguration defaultCacheConfiguration,
                                      Map<String, RedisCacheConfiguration> initialCacheConfigurations,
                                      CacheProperties properties,
                                      StringRedisTemplate redisTemplate,
                                      CacheGovernanceMetrics metrics,
                                      ResilienceGuard resilienceGuard) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations);
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.resilienceGuard = resilienceGuard;
    }

    @Override
    protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfiguration) {
        return new ResilientRedisCache(name, getCacheWriter(), cacheConfiguration,
                properties, redisTemplate, metrics, resilienceGuard.breaker(CACHE_RESOURCE));
    }
}
