package com.geekmall.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内限流执行器：<b>滑动窗口计数</b> + <b>在途并发控制</b>，全程无网络往返。
 *
 * <h3>为什么是滑动窗口而不是固定窗口</h3>
 * <p>固定窗口在窗口边界的瞬时放行量最多可达阈值的 2 倍（例如 100/s 的规则，
 * 在 0.99s~1.01s 之间可能通过 200 次），这正是「突发流量」最需要被削掉的形态。
 * 这里用经典的双窗口加权估算消除该突刺：</p>
 * <pre>
 * 估算值 = 上一窗口计数 × 权重 + 当前窗口计数
 * 权重   = 1 - 当前窗口已过去的时间比例     （越接近窗口末，上一窗口权重越低）
 * </pre>
 * <p>代价只是每窗口多存一个计数器，且无需 ZSET，比 Redis 滑动窗口廉价得多。</p>
 *
 * <h3>并发控制</h3>
 * <p>频率限制管的是「单位时间多少次」，但挡不住「同一时刻有 N 个慢请求在跑」。
 * 对上传、下单、看板这类开销大的接口，额外用最大在途数兜住线程与连接池。
 * 计数必须在请求结束后释放，见 {@link RateLimitInterceptor} 的
 * {@code afterCompletion} / {@code afterConcurrentHandlingStarted}（异步请求走后者）。</p>
 *
 * <p>窗口与并发计数都按 key 存放，使用 Caffeine 做容量上限与访问过期回收，
 * 避免被随机 key（例如海量伪造 IP）撑爆内存。</p>
 */
@Slf4j
@Component
public class LocalRateLimiter {

    /** 窗口空闲这么久就回收；需大于策略表中最长的窗口（当前为 60s）。 */
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(5);

    private final Cache<String, SlidingWindow> windows;
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public LocalRateLimiter(RateLimitProperties properties) {
        this.windows = Caffeine.newBuilder()
                .maximumSize(Math.max(1_000, properties.getLocalMaxKeys()))
                .expireAfterAccess(IDLE_EVICTION)
                .build();
    }

    /**
     * 滑动窗口判定。
     *
     * @return 放行时 {@code remaining} 为剩余额度；拒绝时 {@code retryAfterSeconds} 为本窗口剩余秒数
     */
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        long windowMs = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        long windowStart = now / windowMs * windowMs;

        SlidingWindow window = windows.get(key, k -> new SlidingWindow(windowStart));

        synchronized (window) {
            window.rollIfNeeded(windowStart, windowMs);

            double previousWeight = 1.0 - (double) (now - windowStart) / windowMs;
            double estimated = window.previous * Math.max(0.0, previousWeight) + window.current;

            if (estimated + 1 > limit) {
                long retryAfter = Math.max(1, (windowStart + windowMs - now) / 1000L);
                return RateLimitResult.reject(limit, retryAfter);
            }

            window.current++;
            long remaining = (long) Math.max(0, limit - (estimated + 1));
            return RateLimitResult.allow(limit, remaining);
        }
    }

    /**
     * 尝试占用一个并发槽位。
     *
     * @return {@code false} 表示当前在途请求已达上限，调用方应拒绝本次请求
     */
    public boolean tryAcquireConcurrent(String key, int maxConcurrent) {
        if (maxConcurrent <= 0) {
            return true;
        }
        AtomicInteger counter = inFlight.computeIfAbsent(key, k -> new AtomicInteger());
        if (counter.incrementAndGet() > maxConcurrent) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 释放并发槽位。计数归零时把 key 从 map 里移除，避免长期累积。
     *
     * <p>用 {@code computeIfPresent} 保证「递减 + 判断 + 移除」对同一个 map 桶是原子的。</p>
     */
    public void releaseConcurrent(String key) {
        inFlight.computeIfPresent(key, (k, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    }

    /** 当前在途请求数（测试与诊断用）。 */
    public int inFlightCount(String key) {
        AtomicInteger counter = inFlight.get(key);
        return counter == null ? 0 : counter.get();
    }

    /** 单个计数窗口。只在 {@code synchronized} 块内修改。 */
    private static final class SlidingWindow {

        private long windowStart;
        private long current;
        private long previous;

        private SlidingWindow(long windowStart) {
            this.windowStart = windowStart;
        }

        /** 进入新窗口时把当前计数滚动为「上一窗口」，并清零当前计数。 */
        private void rollIfNeeded(long newWindowStart, long windowMs) {
            if (windowStart == newWindowStart) {
                return;
            }
            // 只有紧邻的上一个窗口才需要保留：跨越多个窗口后其权重早已为 0
            previous = (windowStart == newWindowStart - windowMs) ? current : 0L;
            current = 0L;
            windowStart = newWindowStart;
        }
    }
}
