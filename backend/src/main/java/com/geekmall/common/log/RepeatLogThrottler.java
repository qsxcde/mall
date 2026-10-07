package com.geekmall.common.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重复日志节流：同一「错误指纹」在一个时间窗口内只打一次完整堆栈。
 *
 * <p>解决的是日志放大：兜底异常分支原本对<b>每一个</b>非预期异常都打完整堆栈，
 * 一旦出现高频失败（接口被刷、下游持续抖断、循环里出错），日志量会瞬间涨两个数量级，
 * 而真正有用的那条错误反而被淹没。这类问题不能靠"少打日志"解决——错误必须可见，
 * 只是<b>重复的堆栈没有信息量</b>。</p>
 *
 * <p>节流语义（窗口自首次出现起算，不做滑动）：</p>
 * <ul>
 *   <li>窗口内首次 → {@code firstInWindow = true}，调用方打完整堆栈；</li>
 *   <li>窗口内后续 → {@code false}，调用方只记一行摘要 + 累计次数；</li>
 *   <li>窗口过期后再次出现 → 视为新的首次，重新放行完整堆栈。</li>
 * </ul>
 *
 * <p>用 Caffeine 而不是手写 Map：自动过期 + 容量上限，避免被构造出大量互不相同的
 * 异常指纹把内存撑爆。</p>
 */
@Component
public class RepeatLogThrottler {

    private final Cache<String, AtomicInteger> counters;

    public RepeatLogThrottler(@Value("${mall.logging.repeat-log-window-seconds:60}") long windowSeconds,
                              @Value("${mall.logging.repeat-log-max-keys:10000}") long maxKeys) {
        this.counters = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(maxKeys)
                .build();
    }

    /**
     * 记一次出现。
     *
     * @param fingerprint 错误指纹（建议「异常类型 + 崩溃点」）
     * @return 是否窗口内首次，以及窗口内的累计次数
     */
    public Decision record(String fingerprint) {
        AtomicInteger counter = counters.get(fingerprint, key -> new AtomicInteger());
        int count = counter.incrementAndGet();
        return new Decision(count == 1, count);
    }

    /** 节流判定结果。 */
    public record Decision(boolean firstInWindow, int count) {
    }
}
