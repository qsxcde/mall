package com.geekmall.common.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量熔断器：Redis 连续不可用时跳过缓存，避免把「缓存故障」放大成「全站故障」。
 *
 * <p>为什么缓存层也需要熔断：Lettuce 在连接不可用时，每个请求都要等满
 * {@code spring.data.redis.timeout}（本项目 3s）才失败。若 Redis 挂掉而缓存层不熔断，
 * 每个请求都会先卡 3s 再回源数据库 —— 数据库会被「排队 3s 后同时涌入」的流量打穿。
 * 熔断后请求直接走本地 L1 / 数据库，把故障影响限制在「失去缓存加速」这一层。</p>
 *
 * <p>状态机：</p>
 * <pre>
 * CLOSED（正常放行）
 *   └─ 连续失败达阈值 → OPEN（openDuration 内直接跳过，不再尝试 Redis）
 *        └─ 窗口结束 → HALF_OPEN（只放一个探测请求）
 *             ├─ 探测成功 → CLOSED
 *             └─ 探测失败 / 探测超时未回 → 重新 OPEN
 * </pre>
 *
 * <p>半开态用 CAS 抢「探测名额」，保证恢复瞬间不会被全部积压请求同时冲击。</p>
 */
public class CacheCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断窗口截止时间戳；0 表示未熔断。 */
    private final AtomicLong openUntil = new AtomicLong();
    /** 探测名额的截止时间戳；0 表示当前没有探测在跑。 */
    private final AtomicLong probeDeadline = new AtomicLong();

    public CacheCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1, openDuration.toMillis());
    }

    /** 是否允许访问 Redis。返回 false 表示应直接降级，不再尝试 Redis。 */
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        long deadline = openUntil.get();
        if (deadline == 0L) {
            return true;
        }
        if (now < deadline) {
            return false;
        }
        return acquireProbe(now);
    }

    private boolean acquireProbe(long now) {
        long myDeadline = now + openMillis;
        if (probeDeadline.compareAndSet(0L, myDeadline)) {
            return true;
        }
        long current = probeDeadline.get();
        // 探测请求迟迟未回（线程卡死 / 请求被丢弃）时回收名额，避免熔断永久卡在半开态
        if (current != 0L && now >= current) {
            return probeDeadline.compareAndSet(current, myDeadline);
        }
        return false;
    }

    /** 请求成功：关闭熔断并清零计数。 */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil.set(0L);
        probeDeadline.set(0L);
    }

    /** 请求失败：累计失败数，达到阈值则打开熔断窗口。 */
    public void recordFailure() {
        probeDeadline.set(0L);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil.set(System.currentTimeMillis() + openMillis);
            consecutiveFailures.set(0);
        }
    }

    /** 熔断当前是否处于打开（跳过 Redis）状态。 */
    public boolean isOpen() {
        long deadline = openUntil.get();
        return deadline != 0L && System.currentTimeMillis() < deadline;
    }
}
