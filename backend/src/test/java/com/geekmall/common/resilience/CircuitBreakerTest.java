package com.geekmall.common.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用熔断器单元测试。
 *
 * <p>与旧的「连续失败计数」实现相比，这一版多了两个判定维度，也正是本测试要盯住的地方：</p>
 * <ul>
 *   <li><b>最小样本数</b>：低流量下样本不足不得熔断（否则半夜一个偶发异常就切断依赖）；</li>
 *   <li><b>慢调用率</b>：下游不报错、只是很慢时也必须熔断（否则线程池被占满，故障照样扩散）。</li>
 * </ul>
 */
@DisplayName("CircuitBreaker 滑动窗口熔断器")
class CircuitBreakerTest {

    private ResilienceProperties.Resource config;

    @BeforeEach
    void setUp() {
        config = new ResilienceProperties.Resource();
        config.setMinimumCalls(4);
        config.setFailureRateThreshold(50f);
        config.setSlowCallDuration(Duration.ofSeconds(1));
        config.setSlowCallRateThreshold(80f);
        config.setSlidingWindow(Duration.ofSeconds(30));
        config.setWaitDurationInOpen(Duration.ofMillis(80));
        config.setPermittedCallsInHalfOpen(2);
    }

    private CircuitBreaker breaker() {
        return new CircuitBreaker("test", config, null);
    }

    /** 连续制造 n 次失败调用（每次都会申请许可）。 */
    private void fail(CircuitBreaker breaker, int times) {
        for (int i = 0; i < times; i++) {
            breaker.tryAcquire();
            breaker.recordFailure(Duration.ofMillis(10));
        }
    }

    @Nested
    @DisplayName("关闭态判定")
    class Closed {

        @Test
        @DisplayName("初始状态放行")
        void shouldAllowByDefault() {
            CircuitBreaker breaker = breaker();

            assertThat(breaker.tryAcquire()).isTrue();
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("样本数未达最小调用数时不熔断，避免低流量误熔断")
        void shouldNotOpenBelowMinimumCalls() {
            CircuitBreaker breaker = breaker();

            fail(breaker, 3);

            assertThat(breaker.getState())
                    .as("min=4，只有 3 次失败（即使失败率 100%）也不应熔断")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("失败率低于阈值不熔断")
        void shouldNotOpenWhenFailureRateBelowThreshold() {
            CircuitBreaker breaker = breaker();

            breaker.tryAcquire();
            breaker.recordSuccess(Duration.ofMillis(10));
            breaker.tryAcquire();
            breaker.recordSuccess(Duration.ofMillis(10));
            breaker.tryAcquire();
            breaker.recordSuccess(Duration.ofMillis(10));
            breaker.tryAcquire();
            breaker.recordFailure(Duration.ofMillis(10));

            assertThat(breaker.getState()).as("4 次调用 1 次失败 = 25%").isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("失败率达到阈值即熔断")
        void shouldOpenWhenFailureRateExceeded() {
            CircuitBreaker breaker = breaker();

            fail(breaker, 4);

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(breaker.tryAcquire()).as("打开后不再放行").isFalse();
        }

        @Test
        @DisplayName("下游不报错但一直很慢时同样熔断（慢调用率超阈值）")
        void shouldOpenOnSlowCallsWithoutFailures() {
            CircuitBreaker breaker = breaker();
            config.setSlowCallRateThreshold(60f);
            Duration verySlow = Duration.ofSeconds(5);

            for (int i = 0; i < 4; i++) {
                breaker.tryAcquire();
                breaker.recordSuccess(verySlow);
            }

            assertThat(breaker.getState())
                    .as("4 次调用全部成功但全部超时，慢调用率 100% 应触发熔断")
                    .isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("慢调用阈值为 0 时关闭慢调用判定")
        void slowCallCheckCanBeDisabled() {
            config.setSlowCallRateThreshold(0f);
            CircuitBreaker breaker = breaker();

            for (int i = 0; i < 4; i++) {
                breaker.tryAcquire();
                breaker.recordSuccess(Duration.ofSeconds(5));
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("熔断关闭时不介入")
        void disabledBreakerShouldAlwaysAllow() {
            config.setEnabled(false);
            CircuitBreaker breaker = breaker();

            fail(breaker, 20);

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.tryAcquire()).isTrue();
        }
    }

    @Nested
    @DisplayName("半开探测")
    class HalfOpen {

        @Test
        @DisplayName("窗口结束后进入半开，且只放行配置的探测数")
        void shouldLimitHalfOpenPermits() throws InterruptedException {
            CircuitBreaker breaker = breaker();
            fail(breaker, 4);
            assertThat(breaker.tryAcquire()).isFalse();

            Thread.sleep(120);

            assertThat(breaker.tryAcquire()).as("第一个探测").isTrue();
            assertThat(breaker.tryAcquire()).as("第二个探测").isTrue();
            assertThat(breaker.tryAcquire()).as("超过 permittedCallsInHalfOpen=2 后不再放行").isFalse();
        }

        @Test
        @DisplayName("探测全部成功则恢复 CLOSED")
        void shouldCloseAfterSuccessfulProbes() throws InterruptedException {
            CircuitBreaker breaker = breaker();
            fail(breaker, 4);
            Thread.sleep(120);

            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordSuccess(Duration.ofMillis(10));
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordSuccess(Duration.ofMillis(10));

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.tryAcquire()).isTrue();
        }

        @Test
        @DisplayName("探测失败则重新打开")
        void shouldReopenWhenProbeFails() throws InterruptedException {
            CircuitBreaker breaker = breaker();
            fail(breaker, 4);
            Thread.sleep(120);

            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure(Duration.ofMillis(10));

            // 首个探测就失败即达到失败率阈值（1/1）→ 立即重新打开，不必等剩余探测返回
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(breaker.tryAcquire()).isFalse();
        }

        @Test
        @DisplayName("探测请求迟迟不返回时回收名额，避免熔断永久卡在半开态")
        void staleProbeShouldBeReclaimed() throws InterruptedException {
            // 窗口与探测超时同源（windowMillis），因此把窗口调小以便快速触发回收
            config.setSlidingWindow(Duration.ofSeconds(1));
            config.setWaitDurationInOpen(Duration.ofMillis(50));
            CircuitBreaker breaker = breaker();
            fail(breaker, 4);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            Thread.sleep(80);
            assertThat(breaker.tryAcquire()).isTrue();

            // 状态转换是惰性的（不启后台线程），因此需要再申请一次许可来推进状态机：
            // 这次申请会发现探测已超时，回收名额并重新打开
            Thread.sleep(1_200);
            assertThat(breaker.tryAcquire()).as("探测超时后不再放行").isFalse();
            assertThat(breaker.getState())
                    .as("探测超时未返回，判定为失败并重新打开")
                    .isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("统计快照")
    class Snapshot {

        @Test
        @DisplayName("快照反映窗口内的调用/失败/慢调用与对应比率")
        void shouldReportWindowStatistics() {
            CircuitBreaker breaker = breaker();

            breaker.tryAcquire();
            breaker.recordSuccess(Duration.ofMillis(10));
            breaker.tryAcquire();
            breaker.recordFailure(Duration.ofMillis(10));
            breaker.tryAcquire();
            breaker.recordSuccess(Duration.ofSeconds(5));

            CircuitBreaker.Snapshot snapshot = breaker.snapshot();

            assertThat(snapshot.calls()).isEqualTo(3);
            assertThat(snapshot.failures()).isEqualTo(1);
            assertThat(snapshot.slowCalls()).as("失败也算慢调用，加上那次 5s 的成功调用").isEqualTo(2);
            assertThat(snapshot.failureRate()).isCloseTo(33.33, org.assertj.core.data.Offset.offset(0.1));
        }
    }
}
