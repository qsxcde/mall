package com.geekmall.common.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存降级与熔断单元测试（Redis 不可用这条路径）。
 *
 * <p>这条路径在集成测试里很难覆盖（总不能真把 Redis 容器停掉再停掉），
 * 但它恰恰是「高可用性」的核心承诺：<b>缓存挂掉不能导致业务挂掉</b>。
 * 因此用 Mockito 让 Redis 写入/读取直接抛连接异常，验证：</p>
 * <ul>
 *   <li>读失败 → 降级为「未命中」，由调用方回源，不向上抛异常；</li>
 *   <li>写失败 → 吞掉异常，业务正常返回；</li>
 *   <li>连续失败达阈值 → 熔断，后续请求<b>不再</b>触碰 Redis（避免每个请求都等满 timeout）。</li>
 * </ul>
 */
@DisplayName("ResilientRedisCache 降级与熔断")
class ResilientRedisCacheTest {

    private static final String CACHE_NAME = "resilientTestCache";

    private RedisCacheWriter writer;
    private CacheProperties properties;
    private CacheGovernanceMetrics metrics;

    @BeforeEach
    void setUp() {
        writer = mock(RedisCacheWriter.class);
        properties = new CacheProperties();
        properties.getLock().setEnabled(false);
        metrics = new CacheGovernanceMetrics(new SimpleMeterRegistry());
    }

    private ResilientRedisCache cache() {
        return new ResilientRedisCache(CACHE_NAME, writer,
                RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(1)),
                properties, mock(StringRedisTemplate.class), metrics);
    }

    private void failAllReads() {
        when(writer.get(anyString(), any(byte[].class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
    }

    @Nested
    @DisplayName("Redis 读失败")
    class ReadFailure {

        @Test
        @DisplayName("降级为「未命中」返回 null，绝不向上抛异常")
        void shouldDegradeToMiss() {
            failAllReads();
            ResilientRedisCache cache = cache();

            assertThatCode(() -> assertThat(cache.get("k")).isNull())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("带 loader 的读取在 Redis 故障时直接回源数据库")
        void shouldFallBackToLoader() {
            failAllReads();
            ResilientRedisCache cache = cache();

            Object value = cache.get("k", () -> "from-database");

            assertThat(value).isEqualTo("from-database");
        }
    }

    @Nested
    @DisplayName("Redis 写失败")
    class WriteFailure {

        @Test
        @DisplayName("写入失败被吞掉，业务调用不受影响")
        void shouldSwallowWriteFailure() {
            ResilientRedisCache cache = cache();
            doThrow(new RedisConnectionFailureException("connection refused"))
                    .when(writer).put(anyString(), any(byte[].class), any(byte[].class), any(Duration.class));

            assertThatCode(() -> cache.put("k", "v")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("熔断")
    class Breaker {

        @Test
        @DisplayName("连续失败达阈值后跳过 Redis，不再让每个请求都等满 timeout")
        void shouldStopTouchingRedisAfterRepeatedFailures() {
            properties.getBreaker().setFailureThreshold(3);
            failAllReads();
            ResilientRedisCache cache = cache();

            // 前 3 次仍会真实访问 Redis（失败并累计）
            cache.get("k1");
            cache.get("k2");
            cache.get("k3");
            verify(writer, times(3)).get(anyString(), any(byte[].class));

            // 熔断已打开：后续请求不应再触碰 Redis
            cache.get("k4");
            cache.get("k5");
            verify(writer, times(3)).get(anyString(), any(byte[].class));

            assertThat(cache.getBreaker().isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("本地 L1 白名单")
    class LocalCache {

        @Test
        @DisplayName("不在白名单中的缓存不启用 L1")
        void shouldNotEnableLocalCacheForNonWhitelisted() {
            assertThat(cache().isLocalCacheEnabled()).isFalse();
        }

        @Test
        @DisplayName("在白名单中的缓存启用 L1，且写入后立即可本地命中")
        void shouldEnableLocalCacheForWhitelisted() {
            properties.getLocal().setNames(java.util.List.of(CACHE_NAME));
            ResilientRedisCache cache = cache();

            assertThat(cache.isLocalCacheEnabled()).isTrue();
            cache.put("k", "v");
            assertThat(cache.localCacheSize()).isEqualTo(1L);
        }
    }
}
