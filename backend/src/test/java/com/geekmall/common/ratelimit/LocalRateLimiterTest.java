package com.geekmall.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地限流器单元测试：滑动窗口计数与并发槽位。
 */
class LocalRateLimiterTest {

    private static final String KEY = "mall:rate:test:u:1";

    private LocalRateLimiter limiter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLocalMaxKeys(1_000);
        limiter = new LocalRateLimiter(properties);
    }

    /** 等到「当前秒窗口刚开始」再执行，让窗口边界断言稳定可复现。 */
    private static void awaitFreshWindow() throws InterruptedException {
        long remainder = System.currentTimeMillis() % 1000;
        Thread.sleep(1000 - remainder + 20);
    }

    @Nested
    @DisplayName("滑动窗口频率限制")
    class SlidingWindow {

        @Test
        @DisplayName("窗口内额度用尽后拒绝，并给出重试等待秒数")
        void rejectsAfterLimit() {
            for (int i = 0; i < 3; i++) {
                assertThat(limiter.tryAcquire(KEY, 3, 10).allowed()).as("第 %d 次应放行", i + 1).isTrue();
            }

            RateLimitResult rejected = limiter.tryAcquire(KEY, 3, 10);

            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.remaining()).isZero();
            assertThat(rejected.retryAfterSeconds()).isBetween(1L, 10L);
        }

        @Test
        @DisplayName("放行时回传剩余额度，供前端自适应退避")
        void reportsRemaining() {
            assertThat(limiter.tryAcquire(KEY, 5, 10).remaining()).isEqualTo(4);
            assertThat(limiter.tryAcquire(KEY, 5, 10).remaining()).isEqualTo(3);
            assertThat(limiter.tryAcquire(KEY, 5, 10).limit()).isEqualTo(5);
        }

        @Test
        @DisplayName("不同维度的 key 互不影响（一个用户被限不会牵连其他用户）")
        void keysAreIndependent() {
            String other = "mall:rate:test:u:2";
            for (int i = 0; i < 3; i++) {
                limiter.tryAcquire(KEY, 3, 10);
            }

            assertThat(limiter.tryAcquire(KEY, 3, 10).allowed()).isFalse();
            assertThat(limiter.tryAcquire(other, 3, 10).allowed()).isTrue();
        }

        @Test
        @DisplayName("跨越两个窗口后额度完全恢复")
        void recoversAfterWindowPasses() throws InterruptedException {
            for (int i = 0; i < 2; i++) {
                limiter.tryAcquire(KEY, 2, 1);
            }
            assertThat(limiter.tryAcquire(KEY, 2, 1).allowed()).isFalse();

            // 跨过两个窗口：上一窗口的残留权重已归零
            Thread.sleep(2200);

            assertThat(limiter.tryAcquire(KEY, 2, 1).allowed()).isTrue();
        }

        @Test
        @DisplayName("窗口边界不会放行满额突发（固定窗口在此处会有 2 倍突刺）")
        void smoothsBurstAtWindowBoundary() throws InterruptedException {
            int limit = 20;

            awaitFreshWindow();
            for (int i = 0; i < limit; i++) {
                assertThat(limiter.tryAcquire(KEY, limit, 1).allowed()).isTrue();
            }

            awaitFreshWindow();
            int allowedInNewWindow = 0;
            for (int i = 0; i < limit; i++) {
                if (limiter.tryAcquire(KEY, limit, 1).allowed()) {
                    allowedInNewWindow++;
                }
            }

            assertThat(allowedInNewWindow)
                    .as("新窗口开始时上一窗口的计数仍有残留权重，不应立刻恢复满额")
                    .isLessThan(limit);
        }
    }

    @Nested
    @DisplayName("并发槽位")
    class Concurrency {

        @Test
        @DisplayName("达到并发上限后拒绝，释放后可重新占用")
        void capsInFlightRequests() {
            assertThat(limiter.tryAcquireConcurrent(KEY, 2)).isTrue();
            assertThat(limiter.tryAcquireConcurrent(KEY, 2)).isTrue();
            assertThat(limiter.tryAcquireConcurrent(KEY, 2)).as("超出并发上限应被拒绝").isFalse();
            assertThat(limiter.inFlightCount(KEY)).isEqualTo(2);

            limiter.releaseConcurrent(KEY);

            assertThat(limiter.inFlightCount(KEY)).isEqualTo(1);
            assertThat(limiter.tryAcquireConcurrent(KEY, 2)).isTrue();
        }

        @Test
        @DisplayName("计数归零后移除 key，避免长期累积")
        void removesKeyWhenIdle() {
            limiter.tryAcquireConcurrent(KEY, 2);
            limiter.releaseConcurrent(KEY);

            assertThat(limiter.inFlightCount(KEY)).isZero();
        }

        @Test
        @DisplayName("重复释放不会把计数压成负数")
        void doubleReleaseIsSafe() {
            limiter.tryAcquireConcurrent(KEY, 2);
            limiter.releaseConcurrent(KEY);
            limiter.releaseConcurrent(KEY);
            limiter.releaseConcurrent(KEY);

            assertThat(limiter.inFlightCount(KEY)).isZero();
            // 计数被压成负数的话，下面这个断言会失败 —— 相当于永久放宽了并发上限
            assertThat(limiter.tryAcquireConcurrent(KEY, 1)).isTrue();
        }

        @Test
        @DisplayName("并发上限为 0 表示不限制")
        void zeroMeansUnlimited() {
            assertThat(limiter.tryAcquireConcurrent(KEY, 0)).isTrue();
            assertThat(limiter.tryAcquireConcurrent(KEY, 0)).isTrue();
        }

        @Test
        @DisplayName("多线程竞争下占用数不会超过上限（这正是并发控制的意义）")
        void concurrentAcquireNeverExceedsLimit() throws InterruptedException {
            int maxConcurrent = 4;
            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finishGate = new CountDownLatch(threads);
            AtomicInteger peak = new AtomicInteger();
            AtomicInteger acquired = new AtomicInteger();

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            if (limiter.tryAcquireConcurrent(KEY, maxConcurrent)) {
                                acquired.incrementAndGet();
                                peak.updateAndGet(prev -> Math.max(prev, limiter.inFlightCount(KEY)));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            finishGate.countDown();
                        }
                    });
                }
                startGate.countDown();
                assertThat(finishGate.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(peak.get()).isLessThanOrEqualTo(maxConcurrent);
            assertThat(acquired.get()).isLessThanOrEqualTo(maxConcurrent);
            assertThat(limiter.inFlightCount(KEY)).isLessThanOrEqualTo(maxConcurrent);
        }
    }
}
