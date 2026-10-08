package com.geekmall.common.resilience;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 熔断降级指标。
 *
 * <p>设计要点：<b>「降级次数」与「失败次数」必须分开统计</b>。
 * 只统计失败时，无法回答「这次故障里有多少用户其实是被优雅降级接住了」——
 * 而这恰恰是业务级熔断最核心的价值。</p>
 *
 * <ul>
 *   <li>{@code mall_circuit_state}：0=CLOSED / 1=OPEN / 2=HALF_OPEN（Gauge）</li>
 *   <li>{@code mall_circuit_opened_total}：熔断器打开次数</li>
 *   <li>{@code mall_circuit_rejected_total}：被熔断/并发上限直接挡掉的请求数</li>
 *   <li>{@code mall_resilience_failure_total}：主路径失败次数</li>
 *   <li>{@code mall_resilience_slow_total}：慢调用次数（另见熔断器内部慢调用率）</li>
 *   <li>{@code mall_resilience_degraded_total}：实际走了降级路径的次数</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ResilienceMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 注册熔断状态 Gauge；每个资源只应调用一次（由 Guard 在创建熔断器时触发）。
     *
     * <p>状态码<b>显式映射</b>而不是取 {@code State#ordinal()}：枚举顺序是
     * {@code CLOSED, OPEN, HALF_OPEN}，直接取序号得到的是「0=CLOSED, 1=OPEN, 2=HALF_OPEN」，
     * 一旦有人调整枚举顺序，所有依赖该数值的告警表达式会静默失效。显式映射把它钉死。</p>
     */
    void registerStateGauge(String resource, CircuitBreaker breaker) {
        Gauge.builder("mall_circuit_state", breaker, b -> switch (b.snapshot().state()) {
                    case CLOSED -> 0d;
                    case OPEN -> 1d;
                    case HALF_OPEN -> 2d;
                })
                .tag("resource", resource)
                .description("熔断器状态：0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                .strongReference(true)
                .register(meterRegistry);
    }

    void recordOpened(String resource) {
        meterRegistry.counter("mall_circuit_opened_total", "resource", resource).increment();
    }

    void recordRejected(String resource) {
        meterRegistry.counter("mall_circuit_rejected_total", "resource", resource).increment();
    }

    void recordFailure(String resource, Exception ex) {
        meterRegistry.counter("mall_resilience_failure_total",
                "resource", resource,
                "exception", ex.getClass().getSimpleName()).increment();
    }

    void recordSlow(String resource) {
        meterRegistry.counter("mall_resilience_slow_total", "resource", resource).increment();
    }

    void recordDegraded(String resource) {
        meterRegistry.counter("mall_resilience_degraded_total", "resource", resource).increment();
    }
}
