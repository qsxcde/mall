package com.geekmall.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.geekmall.common.cache.CacheGovernanceMetrics;
import com.geekmall.common.cache.CacheNames;
import com.geekmall.common.cache.CacheProperties;
import com.geekmall.common.cache.JitterTtlFunctionFactory;
import com.geekmall.common.cache.RedisBloomFilter;
import com.geekmall.common.cache.ResilientRedisCacheManager;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.resilience.ResilienceGuard;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * 业务缓存配置：接入具备「穿透 / 击穿 / 雪崩」三防能力的缓存管理器。
 *
 * <p>演进过程：</p>
 * <ol>
 *   <li>最初全仓库没有任何 {@code @Cacheable}，读热点直穿 MySQL；</li>
 *   <li>接入 Redis 缓存并按冷热区分 TTL，但保留了 {@code disableCachingNullValues()}，
 *       且 {@code sync} 与 TTL 抖动都没做 —— 三防全部缺失；</li>
 *   <li>（当前）换成 {@link ResilientRedisCacheManager}，把三防收敛到缓存层统一实现。</li>
 * </ol>
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class CacheConfig {

    /** 未在 {@code mall.cache.ttl} 中声明的缓存使用的兜底 TTL 键名。 */
    private static final String DEFAULT_CACHE_KEY = "__default__";

    private final CacheProperties properties;
    private final JitterTtlFunctionFactory ttlFunctionFactory;

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate stringRedisTemplate,
            CacheGovernanceMetrics metrics,
            ResilienceGuard resilienceGuard) {
        // 注意：这里刻意不调用 disableCachingNullValues()。
        // RedisCacheConfiguration 默认允许缓存 null，这正是「空值缓存」的基础；
        // 空值的过期时间由 JitterTtlFunctionFactory 压到 mall.cache.null-ttl（默认 60s），
        // 既挡住重复的无效查询，又不会让新上架商品被长期挡住。
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttlFunctionFactory.create(DEFAULT_CACHE_KEY))
                .prefixCacheNameWith("mall:cache:")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer()));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        for (String name : CacheNames.ALL) {
            // 每个缓存一份 TtlFunction：既带 TTL 抖动（防雪崩），也负责空值短 TTL（防穿透）
            perCache.put(name, base.entryTtl(ttlFunctionFactory.create(name)));
        }

        RedisCacheWriter cacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);
        return new ResilientRedisCacheManager(
                cacheWriter, base, perCache, properties, stringRedisTemplate, metrics, resilienceGuard);
    }

    /**
     * 商品布隆过滤器：10 万商品 / 1% 误判率 → 位图约 117KB、7 次哈希，内存成本可忽略。
     *
     * <p>误判率不能设得过低：位图大小与哈希次数都由它推导，误判率每降一个数量级，
     * 位图大约要翻一倍。1% 已经足够 —— 漏进来的 1% 由空值缓存兜住。</p>
     */
    @Bean
    public RedisBloomFilter productBloomFilter(StringRedisTemplate redisTemplate) {
        return new RedisBloomFilter(
                redisTemplate, RedisKeys.BLOOM_PRODUCT, RedisKeys.BLOOM_PRODUCT_READY, 100_000, 0.01);
    }

    /**
     * 缓存专用 JSON 序列化器。
     *
     * <p>Jackson 3 已内置 java.time 支持（不再需要 JavaTimeModule），这里只需关闭
     * 时间戳写法，并开启默认类型信息，保证反序列化时能还原出具体类型（含嵌套集合）。</p>
     *
     * <p>Jackson 3 移除了 {@code LaissezFaireSubTypeValidator}，改用
     * {@link BasicPolymorphicTypeValidator}。这里仍允许任意子类型，是为了与升级前
     * 的缓存行为保持一致；后续可收紧为只允许 {@code com.geekmall} / {@code java.*}
     * 白名单，作为独立的安全加固项。</p>
     */
    private GenericJacksonJsonRedisSerializer jsonSerializer() {
        JsonMapper mapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType(Object.class)
                                .build(),
                        DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY)
                .build();
        return new GenericJacksonJsonRedisSerializer(mapper);
    }
}
