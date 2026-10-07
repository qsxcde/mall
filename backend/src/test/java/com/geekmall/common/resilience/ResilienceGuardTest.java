package com.geekmall.common.resilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断降级执行器单元测试。
 *
 * <p>验证三件「降级策略落地时最容易做错」的事：</p>
 * <ol>
 *   <li>熔断打开时能真的改走降级路径；</li>
 *   <li>不允许降级的场景必须<b>明确失败</b>，而不是静默返回空值；</li>
 *   <li>并发上限要先于熔断生效（熔断是事后的，挡不住第一波慢请求）。</li>
 * </ol>
 */
@DisplayName("ResilienceGuard 熔断降级执行器")
class ResilienceGuardTest {

    private ResilienceProperties properties;
    private ResilienceGuard guard;

    @BeforeEach
    void setUp() {
        properties = new ResilienceProperties();
        guard = new ResilienceGuard(properties, new ResilienceMetrics(new SimpleMeterRegistry()));
    }

    /** 配置为「2 次调用 100% 失败即熔断」，便于快速进入 OPEN。 */
    private void fastBreakConfig() {
        ResilienceProperties.Resource config = new ResilienceProperties.Resource();
        config.setMinimumCalls(2);
        config.setFailureRateThreshold(50f);
        config.setSlowCallRateThreshold(0f);
        config.setSlidingWindow(java.time.Duration.ofSeconds(30));
        config.setWaitDurationInOpen(java.time.Duration.ofSeconds(30));
        properties.setDefaults(config);
    }

    private static void boom() {
        throw new IllegalStateException("下游不可用");
    }

    @Test
    @DisplayName("正常路径直接执行并返回结果")
    void shouldExecuteNormally() {
        String result = guard.execute("demo", () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("熔断打开后转入降级路径，业务不感知下游故障")
    void shouldFallBackWhenCircuitOpen() {
        fastBreakConfig();

        // 前两次失败把熔断器打到 OPEN（无降级路径，异常原样上抛）
        assertThatThrownBy(() -> guard.execute("demo", () -> {
            boom();
            return "never";
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.execute("demo", () -> {
            boom();
            return "never";
        })).isInstanceOf(IllegalStateException.class);

        assertThat(guard.breaker("demo").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        String degraded = guard.execute("demo", () -> "primary", () -> "fallback");

        assertThat(degraded)
                .as("熔断打开后不应再请求下游，直接走降级")
                .isEqualTo("fallback");
    }

    @Test
    @DisplayName("不允许降级的场景：熔断打开时明确失败，而不是静默返回兜底值")
    void shouldThrowWhenNoFallbackConfigured() {
        fastBreakConfig();
        assertThatThrownBy(() -> guard.execute("strict", () -> {
            boom();
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.execute("strict", () -> {
            boom();
            return null;
        })).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> guard.execute("strict", () -> "primary"))
                .isInstanceOf(CircuitOpenException.class)
                .hasMessageContaining("strict");
    }

    @Test
    @DisplayName("主路径失败但配置了降级时，直接改走降级")
    void shouldFallBackOnActionFailure() {
        String result = guard.execute("demo",
                () -> {
                    boom();
                    return "primary";
                },
                () -> "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("降级路径同样失败时，抛出可识别的熔断异常（而不是吞掉）")
    void shouldSurfaceFallbackFailure() {
        assertThatThrownBy(() -> guard.execute("demo",
                () -> {
                    boom();
                    return "primary";
                },
                () -> {
                    throw new IllegalStateException("备用存储也挂了");
                }))
                .isInstanceOf(CircuitOpenException.class)
                .hasMessageContaining("降级路径同样失败");
    }

    @Test
    @DisplayName("并发达上限时降级，而不是无限排队")
    void shouldDegradeWhenConcurrencyExceeded() throws Exception {
        ResilienceProperties.Resource config = new ResilienceProperties.Resource();
        config.setMaxConcurrentCalls(1);
        properties.setDefaults(config);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> guard.execute("limited", () -> {
                entered.countDown();
                release.await();
                return "primary";
            }, () -> "fallback"));

            assertThat(entered.await(2, TimeUnit.SECONDS)).as("第一个请求已进入").isTrue();

            assertThat(guard.execute("limited", () -> "primary-2", () -> "fallback-2"))
                    .as("信号量已被占用，第二个请求应直接降级")
                    .isEqualTo("fallback-2");

            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("primary");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("总开关关闭时完全透传：不降级、不计熔断")
    void shouldPassThroughWhenDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> guard.execute("demo",
                () -> {
                    boom();
                    return "primary";
                },
                () -> "fallback"))
                .as("关闭熔断后，降级逻辑不应生效，异常应原样抛出")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("同一资源名返回同一个熔断器实例（缓存层与业务层共用状态）")
    void shouldShareBreakerPerResource() {
        assertThat(guard.breaker("shared")).isSameAs(guard.breaker("shared"));
        assertThat(guard.breaker("shared")).isNotSameAs(guard.breaker("other"));
    }
}
