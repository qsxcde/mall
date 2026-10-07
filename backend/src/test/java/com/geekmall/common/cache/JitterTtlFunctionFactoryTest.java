package com.geekmall.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存 TTL 策略单元测试。
 *
 * <p>这个类同时负责两件事，因此两条断言线都要覆盖：</p>
 * <ul>
 *   <li><b>空值短 TTL</b>（防穿透）：null 值必须用很短的 TTL，不能用实体 TTL；</li>
 *   <li><b>TTL 抖动</b>（防雪崩）：每个写入 key 的过期时间要随机浮动、但要落在可控区间内。</li>
 * </ul>
 */
@DisplayName("JitterTtlFunctionFactory TTL 策略")
class JitterTtlFunctionFactoryTest {

    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    private JitterTtlFunctionFactory factory() {
        CacheProperties properties = new CacheProperties();
        properties.setNullTtl(NULL_TTL);
        properties.setJitterRatio(0.2);
        return new JitterTtlFunctionFactory(properties);
    }

    @Nested
    @DisplayName("空值 TTL（防穿透）")
    class NullValue {

        @Test
        @DisplayName("值为 null（空值缓存）时使用 nullTtl，而不是实体 TTL")
        void shouldUseNullTtlForNullValue() {
            var ttlFunction = factory().create(CacheNames.PRODUCT_DETAIL);

            assertThat(ttlFunction.getTimeToLive("1", null)).isEqualTo(NULL_TTL);
        }

        @Test
        @DisplayName("空值 TTL 必须短于实体 TTL，否则新上架商品会被长期挡在 404")
        void nullTtlShouldBeShorterThanEntityTtl() {
            var ttlFunction = factory().create(CacheNames.PRODUCT_DETAIL);

            assertThat(ttlFunction.getTimeToLive("1", null))
                    .isLessThan(ttlFunction.getTimeToLive("1", new Object()));
        }

        @Test
        @DisplayName("非 null 值不使用 nullTtl")
        void normalValueShouldNotUseNullTtl() {
            var ttlFunction = factory().create(CacheNames.PRODUCT_DETAIL);

            assertThat(ttlFunction.getTimeToLive("1", new Object())).isNotEqualTo(NULL_TTL);
        }
    }

    @Nested
    @DisplayName("TTL 抖动（防雪崩）")
    class Jitter {

        @Test
        @DisplayName("抖动结果落在 ±ratio 区间内")
        void shouldStayWithinRatioBound() {
            Duration base = Duration.ofMinutes(10);

            for (int i = 0; i < 500; i++) {
                Duration ttl = JitterTtlFunctionFactory.jitter(base, 0.2);
                assertThat(ttl).isBetween(Duration.ofMinutes(8), Duration.ofMinutes(12));
            }
        }

        @Test
        @DisplayName("抖动要产生足够多的不同取值，否则打散不了「集中失效」")
        void shouldProduceDiverseValues() {
            Set<Long> values = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                values.add(JitterTtlFunctionFactory.jitter(Duration.ofMinutes(10), 0.2).toMillis());
            }

            assertThat(values).hasSizeGreaterThan(50);
        }

        @Test
        @DisplayName("比例为 0 或负数时保持原 TTL（可关闭抖动）")
        void zeroRatioShouldKeepBaseTtl() {
            assertThat(JitterTtlFunctionFactory.jitter(Duration.ofMinutes(5), 0))
                    .isEqualTo(Duration.ofMinutes(5));
            assertThat(JitterTtlFunctionFactory.jitter(Duration.ofMinutes(5), -1))
                    .isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("抖动后 TTL 恒为正数，绝不产生 0 或负 TTL")
        void shouldNeverProduceNonPositiveTtl() {
            for (int i = 0; i < 200; i++) {
                assertThat(JitterTtlFunctionFactory.jitter(Duration.ofMillis(1), 0.9))
                        .isGreaterThanOrEqualTo(Duration.ofMillis(1));
            }
        }

        @Test
        @DisplayName("未声明的缓存名回退到 defaultTtl")
        void unknownCacheShouldFallBackToDefaultTtl() {
            var ttlFunction = factory().create("not-declared-cache");

            assertThat(ttlFunction.getTimeToLive("k", new Object()))
                    .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(6));
        }

        @Test
        @DisplayName("按缓存名取到各自的基础 TTL：分类树最长、商品详情最短")
        void shouldResolvePerCacheTtl() {
            var factory = factory();
            Duration category = factory.create(CacheNames.CATEGORY_TREE).getTimeToLive("k", new Object());
            Duration detail = factory.create(CacheNames.PRODUCT_DETAIL).getTimeToLive("k", new Object());

            assertThat(category).isGreaterThan(detail);
            assertThat(category).isBetween(Duration.ofMinutes(24), Duration.ofMinutes(36));
            assertThat(detail).isBetween(Duration.ofMinutes(2), Duration.ofMinutes(4));
        }
    }
}
