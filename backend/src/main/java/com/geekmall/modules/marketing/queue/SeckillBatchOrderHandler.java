package com.geekmall.modules.marketing.queue;

import com.geekmall.modules.marketing.config.SeckillProperties;
import com.geekmall.modules.marketing.dto.BatchOrderOutcome;
import com.geekmall.modules.marketing.dto.BatchPersistResult;
import com.geekmall.modules.marketing.queue.SeckillOrderQueue.Delivery;
import com.geekmall.modules.marketing.service.SeckillBatchPersistService;
import com.geekmall.modules.marketing.service.impl.SeckillServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 秒杀批量落库处理器：消费端「拉一批 → 一个事务落库 → 批量确认」的编排。
 *
 * <p><b>只负责批量路径</b>：已终态、超重投、毒消息的兜底动作仍交回消费者，
 * 处理器通过返回值告诉消费者「哪些要转死信、哪些要回退逐条」。</p>
 *
 * <p><b>批量失败不在这里补偿</b>：事务异常时直接返回逐条 fallback，
 * 让现有 {@code handle()} 走原来的「扣库存 → 建单 → 失败回补」路径，
 * 保证只有一条补偿链路，不会出现批量与逐条各补一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillBatchOrderHandler {

    private final SeckillBatchPersistService batchPersistService;
    private final SeckillResultStore resultStore;
    private final SeckillServiceImpl seckillService;
    private final SeckillOrderQueue queue;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;

    /** 批量处理结果：死信由消费者处理，fallback 由消费者逐条重试。 */
    public record BatchHandleResult(List<Delivery> deadLetters, List<Delivery> fallback) {}

    public BatchHandleResult handle(String consumer, List<Delivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return new BatchHandleResult(List.of(), List.of());
        }
        List<Delivery> acked = new ArrayList<>(deliveries.size());
        List<Delivery> deadLetters = new ArrayList<>();
        List<Delivery> processable = new ArrayList<>(deliveries.size());

        for (Delivery delivery : deliveries) {
            SeckillOrderMessage message = delivery.message();
            Optional<SeckillGrabResult> existing = resultStore.find(message.requestId());
            if (existing.isPresent() && existing.get().isFinished()) {
                acked.add(delivery);
                continue;
            }
            if (delivery.deliveries() > properties.getAsync().getMaxDeliveries()) {
                deadLetters.add(delivery);
                continue;
            }
            processable.add(delivery);
        }

        if (processable.isEmpty()) {
            safeAck(acked);
            return new BatchHandleResult(deadLetters, List.of());
        }

        long startNanos = System.nanoTime();
        meterRegistry.summary("mall_seckill_batch_size").record(processable.size());

        BatchPersistResult result;
        try {
            result = batchPersistService.persist(
                    processable.stream().map(Delivery::message).toList());
        } catch (Exception ex) {
            // 批量事务已回滚：交回逐条路径，由它完成扣减 / 回补 / 结果写入
            meterRegistry
                    .counter("mall_seckill_batch_commit_total", "result", "fallback")
                    .increment();
            meterRegistry
                    .timer("mall_seckill_batch_duration_seconds")
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            log.error("[秒杀批量] 批量落库失败，回退逐条处理：条数={}", processable.size(), ex);
            safeAck(acked);
            return new BatchHandleResult(deadLetters, processable);
        }

        Map<String, BatchOrderOutcome> byRequest = new LinkedHashMap<>();
        for (BatchOrderOutcome outcome : result.outcomes()) {
            byRequest.put(outcome.requestId(), outcome);
        }

        List<Delivery> fallback = new ArrayList<>();
        int success = 0;
        int failed = 0;
        for (Delivery delivery : processable) {
            SeckillOrderMessage message = delivery.message();
            BatchOrderOutcome outcome = byRequest.get(message.requestId());
            if (outcome == null) {
                // 理论上不会发生：批量服务对每条唯一 requestId 都应给出结果。
                // 真的缺失时不做猜测，交回逐条路径重新判定。
                fallback.add(delivery);
                continue;
            }
            if (outcome.success()) {
                resultStore.save(SeckillGrabResult.success(message.requestId(), message.userId(), outcome.orderNo()));
                success++;
            } else {
                seckillService.releaseReservation(message.itemId(), message.userId(), message.bucketNo());
                resultStore.save(SeckillGrabResult.failed(message.requestId(), message.userId(), outcome.message()));
                failed++;
            }
            acked.add(delivery);
        }

        safeAck(acked);
        meterRegistry
                .counter("mall_seckill_batch_commit_total", "result", "success")
                .increment();
        if (success > 0) {
            meterRegistry
                    .counter("mall_seckill_batch_orders_total", "result", "success")
                    .increment(success);
        }
        if (failed > 0) {
            meterRegistry
                    .counter("mall_seckill_batch_orders_total", "result", "failed")
                    .increment(failed);
        }
        meterRegistry
                .timer("mall_seckill_batch_duration_seconds")
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        log.info("[秒杀批量] 批量落库完成：成功={}, 业务失败={}, 回退={}, consumer={}", success, failed, fallback.size(), consumer);
        return new BatchHandleResult(deadLetters, fallback);
    }

    /** 确认失败不能反过来把已提交的批次判成失败：留给 reclaimStale 重投即可。 */
    private void safeAck(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        try {
            queue.ackAll(deliveries);
        } catch (Exception ex) {
            log.error("[秒杀批量] 批量确认失败，消息将由 reclaimStale 重投：条数={}", deliveries.size(), ex);
        }
    }
}
