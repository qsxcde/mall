package com.geekmall.common.cache;

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

    private final CacheProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final CacheGovernanceMetrics metrics;

    public ResilientRedisCacheManager(RedisCacheWriter cacheWriter,
                                      RedisCacheConfiguration defaultCacheConfiguration,
                                      Map<String, RedisCacheConfiguration> initialCacheConfigurations,
                                      CacheProperties properties,
                                      StringRedisTemplate redisTemplate,
                                      CacheGovernanceMetrics metrics) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations);
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    @Override
    protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfiguration) {
        return new ResilientRedisCache(name, getCacheWriter(), cacheConfiguration,
                properties, redisTemplate, metrics);
    }
}
