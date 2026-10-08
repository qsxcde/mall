package com.geekmall.modules.marketing.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.ToLongFunction;

/**
 * 秒杀削峰队列的可观测指标。
 *
 * <p>削峰把「排队」从请求线程搬到了队列里 —— 于是<b>队列积压成了新的失败面</b>：
 * 消费者卡住时用户会一直停在「排队中」，而这个状态在业务日志里几乎看不出来。
 * 本类把队列状态暴露成指标，让「积压」在告警里可见，而不是等用户投诉。</p>
 *
 * <ul>
 *   <li>{@code mall_seckill_queue_pending}：已投递未确认条数 —— <b>核心积压信号</b>，
 *       消费者卡住时持续上涨；</li>
 *   <li>{@code mall_seckill_queue_length}：流条目总数（含已确认未裁剪的）；</li>
 *   <li>{@code mall_seckill_queue_consumers}：组内活跃消费者数，掉到 0 说明消费端全挂了；</li>
 *   <li>{@code mall_seckill_dead_letter_total}：转死信条数（重投达上限仍未成功）。</li>
 * </ul>
 *
 * <p>取值一律走 {@link SeckillOrderQueue#stats()}，失败时返回 {@code NaN} 而不是 0 ——
 * <b>「取不到」和「确实是 0」必须在图上是两种样子</b>，否则 Redis 抖动会被误读成队列排空。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillQueueMetrics {

    private final MeterRegistry meterRegistry;
    private final SeckillOrderQueue queue;

    @PostConstruct
    void registerGauges() {
        register("mall_seckill_queue_pending", "已投递但未确认的秒杀消息数（在途积压）",
                SeckillOrderQueue.QueueStats::pending);
        register("mall_seckill_queue_length", "秒杀订单流条目总数",
                SeckillOrderQueue.QueueStats::length);
        register("mall_seckill_queue_consumers", "秒杀订单消费者组内活跃消费者数",
                SeckillOrderQueue.QueueStats::consumers);
        log.debug("秒杀队列指标已注册：mall_seckill_queue_{pending,length,consumers}");
    }

    /** 记录一条转入死信的消息（重投达上限仍未成功，不再重试）。 */
    public void recordDeadLetter() {
        meterRegistry.counter("mall_seckill_dead_letter_total").increment();
    }

    private void register(String name, String description, ToLongFunction<SeckillOrderQueue.QueueStats> extractor) {
        Gauge.builder(name, queue, q -> {
                    SeckillOrderQueue.QueueStats stats = q.stats();
                    // 取不到时给 NaN：Prometheus 会记录为 NaN，越界告警一律不触发，且与真实 0 可区分
                    return stats.available() ? (double) extractor.applyAsLong(stats) : Double.NaN;
                })
                .description(description)
                .register(meterRegistry);
    }
}
