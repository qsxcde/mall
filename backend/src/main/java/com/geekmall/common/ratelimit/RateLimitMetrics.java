package com.geekmall.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 限流指标：把「限流是否在起作用、拦住的是哪些规则」变成可观测数字。
 *
 * <p>没有指标时限流只是一段「看得见代码、看不见效果」的逻辑：压测时被拒的请求
 * 混在业务失败里，线上也无法判断是限流太严还是流量真的涨了。</p>
 *
 * <ul>
 *   <li>{@code mall_rate_limit_rejected_total}：按<b>规则名 + 层级 + 原因</b>统计拒绝次数</li>
 * </ul>
 *
 * <p><b>标签只放低基数字段</b>：规则名（约 50 个）、层级（LOCAL/DISTRIBUTED）、
 * 原因（rate=频率超限 / concurrent=并发超限）。<b>刻意不标 IP 与 userId</b> ——
 * 它们是高基数标签，会直接把 Prometheus 的时间序列数量打爆。</p>
 */
@Component
@RequiredArgsConstructor
public class RateLimitMetrics {

    /** 频率超限。 */
    public static final String REASON_RATE = "rate";
    /** 并发超限。 */
    public static final String REASON_CONCURRENT = "concurrent";

    private final MeterRegistry meterRegistry;

    public void recordRejected(RateLimitPolicy policy, String reason) {
        Counter.builder("mall_rate_limit_rejected_total")
                .tag("policy", policy.name())
                .tag("tier", policy.tier().name())
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
