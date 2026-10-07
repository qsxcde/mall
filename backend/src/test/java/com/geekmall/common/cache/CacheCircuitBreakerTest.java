package com.geekmall.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存熔断器单元测试。
 *
 * <p>熔断是「雪崩防护」的最后一环：Redis 整体不可用时，缓存层必须主动放弃，
 * 否则每个请求都要等满 redis timeout，反而把数据库拖垮。这里穷举状态机的每条边。</p>
 */
@DisplayName("CacheCircuitBreaker 熔断状态机")
class CacheCircuitBreakerTest {

    @Nested
    @DisplayName("关闭态")
    class Closed {

        @Test
        @DisplayName("初始状态放行，且没有熔断标记")
        void shouldAllowByDefault() {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(3, Duration.ofSeconds(1));

            assertThat(breaker.allowRequest()).isTrue();
            assertThat(breaker.isOpen()).isFalse();
        }

        @Test
        @DisplayName("成功会清零失败计数，偶发失败不会累积成熔断")
        void successShouldResetFailures() {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(3, Duration.ofSeconds(1));

            breaker.recordFailure();
            breaker.recordFailure();
            breaker.recordSuccess();
            breaker.recordFailure();
            breaker.recordFailure();

            assertThat(breaker.allowRequest()).as("重置后只累计了 2 次失败，未达阈值").isTrue();
            assertThat(breaker.isOpen()).isFalse();
        }

        @Test
        @DisplayName("失败次数未达阈值时仍然放行")
        void belowThresholdShouldStillAllow() {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(3, Duration.ofSeconds(1));

            breaker.recordFailure();
            breaker.recordFailure();

            assertThat(breaker.allowRequest()).isTrue();
        }
    }

    @Nested
    @DisplayName("打开态")
    class Open {

        @Test
        @DisplayName("连续失败达到阈值即熔断，窗口内一律跳过 Redis")
        void shouldOpenAfterThreshold() {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(3, Duration.ofSeconds(5));

            for (int i = 0; i < 3; i++) {
                breaker.recordFailure();
            }

            assertThat(breaker.isOpen()).isTrue();
            assertThat(breaker.allowRequest()).isFalse();
        }
    }

    @Nested
    @DisplayName("半开态")
    class HalfOpen {

        @Test
        @DisplayName("窗口结束后只放一个探测请求，其余仍被拦截")
        void shouldAllowSingleProbe() throws InterruptedException {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(1, Duration.ofMillis(60));
            breaker.recordFailure();
            assertThat(breaker.allowRequest()).isFalse();

            Thread.sleep(90);

            assertThat(breaker.allowRequest()).as("第一个请求作为探测放行").isTrue();
            assertThat(breaker.allowRequest()).as("其余请求不得同时涌入").isFalse();
            assertThat(breaker.allowRequest()).isFalse();
        }

        @Test
        @DisplayName("探测成功则恢复放行")
        void probeSuccessShouldCloseCircuit() throws InterruptedException {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(1, Duration.ofMillis(60));
            breaker.recordFailure();
            Thread.sleep(90);

            assertThat(breaker.allowRequest()).isTrue();
            breaker.recordSuccess();

            assertThat(breaker.isOpen()).isFalse();
            assertThat(breaker.allowRequest()).isTrue();
        }

        @Test
        @DisplayName("探测失败则重新打开熔断窗口")
        void probeFailureShouldReopen() throws InterruptedException {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(1, Duration.ofMillis(60));
            breaker.recordFailure();
            Thread.sleep(90);

            assertThat(breaker.allowRequest()).isTrue();
            breaker.recordFailure();

            assertThat(breaker.isOpen()).isTrue();
            assertThat(breaker.allowRequest()).isFalse();
        }

        @Test
        @DisplayName("探测请求超时未回时回收名额，避免熔断永久卡死")
        void staleProbeShouldBeReclaimed() throws InterruptedException {
            CacheCircuitBreaker breaker = new CacheCircuitBreaker(1, Duration.ofMillis(60));
            breaker.recordFailure();
            Thread.sleep(90);

            assertThat(breaker.allowRequest()).as("第一个探测").isTrue();
            assertThat(breaker.allowRequest()).as("探测进行中，暂时不放行").isFalse();

            // 探测请求未回（相当于线程卡死），超过一个 openDuration 后应能重新探测
            Thread.sleep(90);
            assertThat(breaker.allowRequest()).as("回收过期探测名额后重新放行").isTrue();
        }
    }
}
