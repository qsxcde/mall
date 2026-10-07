package com.geekmall.common.resilience;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 滑动窗口熔断器（业务级）。
 *
 * <p>与缓存的 {@code CacheCircuitBreaker}（连续失败计数）相比，本实现的判定维度是
 * <b>滑动窗口内的失败率 + 慢调用率</b>，原因是连续计数在两种真实场景下会失效：</p>
 * <ul>
 *   <li><b>低流量误熔断</b>：半夜连续 2 个偶发超时就能把依赖熔断，所以引入
 *       {@code minimumCalls}（样本不足不做判定）；</li>
 *   <li><b>慢而不吞</b>：下游没报错、只是很慢（连接建立了但不返回）时，
 *       连续失败计数会一直是 0，熔断永不触发 —— 而线程池早已被占满。
 *       所以「慢调用」也计入失败判定（{@code slowCallRateThreshold}）。</li>
 * </ul>
 *
 * <p>状态机：CLOSED →（失败率或慢调用率超阈值）→ OPEN →
 * （等待 {@code waitDurationInOpen}）→ HALF_OPEN →（探测通过）→ CLOSED，
 * 探测失败则重新 OPEN。</p>
 *
 * <p>窗口实现：固定 {@value #BUCKETS} 个时间桶的环形数组，写入时按当前时间落桶，
 * 读取时聚合未过期桶。相比「每次判断都加锁统计」，分桶能把写入开销压到几次原子自增。</p>
 */
@Slf4j
public class CircuitBreaker {

    /** 滑动窗口拆成的桶数。10 个桶意味着窗口内的数据有 10 级时间精度。 */
    private static final int BUCKETS = 10;

    private static final int FIELD_CALLS = 0;
    private static final int FIELD_FAILURES = 1;
    private static final int FIELD_SLOW = 2;

    public enum State {
        /** 放行。 */
        CLOSED,
        /** 拒绝（直接降级）。 */
        OPEN,
        /** 半开：只放少量探测请求试探恢复。 */
        HALF_OPEN
    }

    private final String name;
    private final ResilienceProperties.Resource config;
    private final Runnable onOpen;

    private final long windowMillis;
    private final long bucketMillis;

    /** 每个桶 3 个计数（calls / failures / slowCalls），按 {@code bucketIndex * 3 + field} 寻址。 */
    private final AtomicLongArray bucketData = new AtomicLongArray(BUCKETS * 3);
    /** 每个桶的时间起点，用于识别「桶已过期」并在复用时清零。 */
    private final AtomicLongArray bucketStarts = new AtomicLongArray(BUCKETS);
    private final Object bucketLock = new Object();

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong openUntil = new AtomicLong();

    /* 半开态探测信息 */
    private final AtomicInteger halfOpenCalls = new AtomicInteger();
    private final AtomicInteger halfOpenFailures = new AtomicInteger();
    private final AtomicLong halfOpenDeadline = new AtomicLong();

    /**
     * @param name   资源名（会出现在指标与日志里）
     * @param config 该资源的熔断参数
     * @param onOpen 每次「进入 OPEN」时的回调（用于打点），可为 null
     */
    public CircuitBreaker(String name, ResilienceProperties.Resource config, Runnable onOpen) {
        this.name = name;
        this.config = config;
        this.onOpen = onOpen == null ? () -> { } : onOpen;
        this.windowMillis = Math.max(1_000L, config.getSlidingWindow().toMillis());
        this.bucketMillis = Math.max(1L, windowMillis / BUCKETS);
    }

    public String getName() {
        return name;
    }

    public State getState() {
        return state.get();
    }

    public boolean isClosed() {
        return !config.isEnabled() || state.get() == State.CLOSED;
    }

    /**
     * 申请一次调用许可。
     *
     * @return true 表示放行；false 表示应直接走降级（熔断打开 / 半开探测名额已满）
     */
    public boolean tryAcquire() {
        if (!config.isEnabled()) {
            return true;
        }
        State current = state.get();
        if (current == State.OPEN) {
            if (System.currentTimeMillis() < openUntil.get()) {
                return false;
            }
            // 窗口结束：第一个到达的线程负责把状态推进到 HALF_OPEN
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                halfOpenCalls.set(0);
                halfOpenFailures.set(0);
                halfOpenDeadline.set(System.currentTimeMillis() + windowMillis);
                log.info("[熔断器] {} 进入半开态，放行 {} 个探测请求",
                        name, config.getPermittedCallsInHalfOpen());
            }
            current = state.get();
        }
        if (current == State.HALF_OPEN) {
            return acquireHalfOpenPermit();
        }
        return true;
    }

    /**
     * 半开态的探测名额分配。
     *
     * <p>带超时保护：探测请求如果迟迟不返回（线程卡死 / 请求被丢弃），
     * 名额会一直被占着导致熔断永久卡在半开态，因此在超过一个窗口时长后回收。</p>
     */
    private boolean acquireHalfOpenPermit() {
        long deadline = halfOpenDeadline.get();
        if (deadline != 0L && System.currentTimeMillis() > deadline) {
            log.warn("[熔断器] {} 半开探测超时未返回，判定为失败并重新打开", name);
            toOpen();
            return false;
        }
        int permits = Math.max(1, config.getPermittedCallsInHalfOpen());
        return halfOpenCalls.incrementAndGet() <= permits;
    }

    /** 记录一次成功调用。 */
    public void recordSuccess(Duration elapsed) {
        if (!config.isEnabled()) {
            return;
        }
        State current = state.get();
        if (current == State.HALF_OPEN) {
            evaluateHalfOpen();
            return;
        }
        if (current == State.OPEN) {
            return;
        }
        record(elapsed, false);
    }

    /** 记录一次失败调用。 */
    public void recordFailure(Duration elapsed) {
        if (!config.isEnabled()) {
            return;
        }
        State current = state.get();
        if (current == State.HALF_OPEN) {
            halfOpenFailures.incrementAndGet();
            evaluateHalfOpen();
            return;
        }
        if (current == State.OPEN) {
            return;
        }
        record(elapsed, true);
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    private void record(Duration elapsed, boolean failure) {
        boolean slow = failure || isSlow(elapsed);
        addToBucket(System.currentTimeMillis(), failure, slow);
        if (slow) {
            log.debug("[熔断器] {} 记录慢调用 {}ms", name, elapsed == null ? -1 : elapsed.toMillis());
        }
        if (shouldOpen()) {
            toOpen();
        }
    }

    private boolean isSlow(Duration elapsed) {
        Duration threshold = config.getSlowCallDuration();
        if (threshold == null || threshold.isZero() || threshold.isNegative() || elapsed == null) {
            return false;
        }
        return elapsed.compareTo(threshold) >= 0;
    }

    private void addToBucket(long now, boolean failure, boolean slow) {
        long bucketStart = now - (now % bucketMillis);
        int index = (int) ((now / bucketMillis) % BUCKETS);
        if (bucketStarts.get(index) != bucketStart) {
            synchronized (bucketLock) {
                if (bucketStarts.get(index) != bucketStart) {
                    bucketStarts.set(index, bucketStart);
                    bucketData.set(index * 3 + FIELD_CALLS, 0);
                    bucketData.set(index * 3 + FIELD_FAILURES, 0);
                    bucketData.set(index * 3 + FIELD_SLOW, 0);
                }
            }
        }
        bucketData.incrementAndGet(index * 3 + FIELD_CALLS);
        if (failure) {
            bucketData.incrementAndGet(index * 3 + FIELD_FAILURES);
        }
        if (slow) {
            bucketData.incrementAndGet(index * 3 + FIELD_SLOW);
        }
    }

    /** 聚合未过期桶，返回 {@code [calls, failures, slowCalls]}。 */
    private long[] aggregate() {
        long cutoff = System.currentTimeMillis() - windowMillis;
        long calls = 0;
        long failures = 0;
        long slow = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (bucketStarts.get(i) < cutoff) {
                continue;
            }
            calls += bucketData.get(i * 3 + FIELD_CALLS);
            failures += bucketData.get(i * 3 + FIELD_FAILURES);
            slow += bucketData.get(i * 3 + FIELD_SLOW);
        }
        return new long[]{calls, failures, slow};
    }

    private boolean shouldOpen() {
        long[] agg = aggregate();
        long calls = agg[FIELD_CALLS];
        if (calls < Math.max(1, config.getMinimumCalls())) {
            return false;
        }
        if (exceedsRate(agg[FIELD_FAILURES], calls, config.getFailureRateThreshold())) {
            return true;
        }
        return config.getSlowCallRateThreshold() > 0
                && exceedsRate(agg[FIELD_SLOW], calls, config.getSlowCallRateThreshold());
    }

    private void evaluateHalfOpen() {
        int permits = Math.max(1, config.getPermittedCallsInHalfOpen());
        int calls = halfOpenCalls.get();
        int failures = halfOpenFailures.get();
        if (exceedsRate(failures, calls, config.getFailureRateThreshold())) {
            toOpen();
            return;
        }
        if (calls < permits) {
            return;
        }
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            resetWindow();
            log.info("[熔断器] {} 探测通过（{}/{} 成功），恢复 CLOSED", name, calls - failures, calls);
        }
    }

    private static boolean exceedsRate(long part, long total, float thresholdPercent) {
        if (total <= 0 || thresholdPercent <= 0) {
            return false;
        }
        return part * 100.0 / total >= thresholdPercent;
    }

    private void toOpen() {
        if (state.getAndSet(State.OPEN) == State.OPEN) {
            return;
        }
        long waitMillis = Math.max(1L, config.getWaitDurationInOpen().toMillis());
        openUntil.set(System.currentTimeMillis() + waitMillis);
        halfOpenCalls.set(0);
        halfOpenFailures.set(0);
        halfOpenDeadline.set(0L);

        long[] agg = aggregate();
        log.warn("[熔断器] {} 已打开：窗口内调用 {} 次、失败 {} 次、慢调用 {} 次；"
                        + "接下来 {}ms 内直接降级，不再请求下游",
                name, agg[FIELD_CALLS], agg[FIELD_FAILURES], agg[FIELD_SLOW], waitMillis);
        onOpen.run();
    }

    private void resetWindow() {
        synchronized (bucketLock) {
            for (int i = 0; i < BUCKETS; i++) {
                bucketStarts.set(i, 0L);
                bucketData.set(i * 3 + FIELD_CALLS, 0);
                bucketData.set(i * 3 + FIELD_FAILURES, 0);
                bucketData.set(i * 3 + FIELD_SLOW, 0);
            }
        }
    }

    /** 当前窗口统计快照，用于指标与诊断。 */
    public Snapshot snapshot() {
        long[] agg = aggregate();
        long calls = agg[FIELD_CALLS];
        long failures = agg[FIELD_FAILURES];
        long slow = agg[FIELD_SLOW];
        return new Snapshot(state.get(), calls, failures, slow,
                calls == 0 ? 0d : failures * 100.0 / calls,
                calls == 0 ? 0d : slow * 100.0 / calls);
    }

    public record Snapshot(State state, long calls, long failures, long slowCalls,
                           double failureRate, double slowCallRate) {
    }
}
