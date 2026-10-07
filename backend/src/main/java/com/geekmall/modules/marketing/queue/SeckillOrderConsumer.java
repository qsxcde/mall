package com.geekmall.modules.marketing.queue;

import com.geekmall.common.exception.BizException;
import com.geekmall.modules.marketing.config.SeckillProperties;
import com.geekmall.modules.marketing.service.impl.SeckillServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀订单落库消费者（削峰模式的执行端）。
 *
 * <p>只在 {@code mall.seckill.async.enabled=true} 时启用 —— 异步化改变了接口契约
 * （返回排队凭证而非订单号），默认关闭可保证「没改前端时行为完全不变」。</p>
 *
 * <h3>幂等：为什么不用「处理前打标记」</h3>
 * <p>常见的「消费前 SETNX 一个 processed 标记」写法有个致命缺陷：<b>处理到一半崩溃</b>时，
 * 标记已经写下但订单没建出来，重投时会被误判为「已处理」而跳过，用户永远等不到结果。</p>
 *
 * <p>这里改用两段保障：</p>
 * <ol>
 *   <li><b>结果终态</b>：处理完成才写 SUCCESS/FAILED。重复投递时看到终态才跳过；</li>
 *   <li><b>订单唯一索引</b>：万一「已建单但未写终态」时崩溃，重投会重新走建单，
 *       被 {@code uk_user_request (user_id, request_id)} 拦下并返回已有订单号。</li>
 * </ol>
 * <p>两者叠加才是完备的：只有前者会漏（崩溃窗口），只有后者会让用户重复等待。</p>
 *
 * <h3>失败处理：区分业务失败与系统故障</h3>
 * <ul>
 *   <li>业务性失败（库存已抢光、重复抢购）→ 回补预扣 + 写终态 + <b>丢弃消息</b>（重投无意义）；</li>
 *   <li>系统性故障（DB 不可用）→ <b>不确认</b>，留在 pending 等 {@code reclaimStale} 重投。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mall.seckill.async.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SeckillOrderConsumer implements SmartLifecycle {

    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(2);

    private final SeckillOrderQueue queue;
    private final SeckillResultStore resultStore;
    private final SeckillServiceImpl seckillService;
    private final SeckillProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger threadCounter = new AtomicInteger();
    /** 回收扫描的节流时间戳，避免每个线程每轮都扫一遍 pending。 */
    private final AtomicLong lastReclaimAt = new AtomicLong();

    private ExecutorService workers;

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int threads = Math.max(1, properties.getAsync().getConsumerThreads());
        workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "seckill-consumer-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < threads; i++) {
            String consumer = consumerName() + "-" + i;
            workers.submit(() -> consumeLoop(consumer));
        }
        log.info("[秒杀削峰] 消费者已启动：线程数={}, 分组前缀={}（削峰只摊平脉冲，不提升数据库吞吐）",
                threads, consumerName());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("[秒杀削峰] 消费者已停止");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /* ------------------------------ 消费循环 ------------------------------ */

    private void consumeLoop(String consumer) {
        Duration block = properties.getAsync().getBlock();
        int batchSize = properties.getAsync().getBatchSize();
        while (running.get()) {
            try {
                reclaimIfDue(consumer);
                List<SeckillOrderQueue.Delivery> deliveries = queue.poll(consumer, batchSize, block);
                for (SeckillOrderQueue.Delivery delivery : deliveries) {
                    if (!running.get()) {
                        return;
                    }
                    handle(consumer, delivery);
                }
            } catch (Exception ex) {
                // 循环级异常（如 Redis 抖动）：退避后重试，绝不让消费线程退出
                log.error("[秒杀削峰] 消费循环异常，{}ms 后重试", ERROR_BACKOFF.toMillis(), ex);
                sleepQuietly();
            }
        }
    }

    /**
     * 周期性回收超时未确认的消息（消费者崩溃 / 处理超时的兜底）。
     *
     * <p>用 CAS 节流：多线程共享一个时间戳，只有抢到的那个线程执行扫描。</p>
     */
    private void reclaimIfDue(String consumer) {
        long now = System.currentTimeMillis();
        long interval = properties.getAsync().getReclaimInterval().toMillis();
        long last = lastReclaimAt.get();
        if (now - last < interval || !lastReclaimAt.compareAndSet(last, now)) {
            return;
        }
        List<SeckillOrderQueue.Delivery> reclaimed = queue.reclaimStale(consumer,
                properties.getAsync().getReclaimMinIdle(), properties.getAsync().getBatchSize());
        for (SeckillOrderQueue.Delivery delivery : reclaimed) {
            if (!running.get()) {
                return;
            }
            handle(consumer, delivery);
        }
    }

    private void handle(String consumer, SeckillOrderQueue.Delivery delivery) {
        SeckillOrderMessage message = delivery.message();
        String requestId = message.requestId();

        Optional<SeckillGrabResult> existing = resultStore.find(requestId);
        if (existing.isPresent() && existing.get().isFinished()) {
            // 重复投递，且上次已处理完 —— 直接确认。这是**唯一**可以跳过处理的情况：
            // 结果处于 QUEUED 或不存在，都意味着上次没处理完，必须重做
            queue.ack(delivery);
            log.debug("[秒杀削峰] 消息已处理过，跳过：requestId={}", requestId);
            return;
        }

        try {
            String orderNo = seckillService.grabInTx(message.userId(), message.itemId(),
                    message.addressId(), requestId);
            resultStore.save(SeckillGrabResult.success(requestId, message.userId(), orderNo));
            queue.ack(delivery);
            log.info("[秒杀削峰] 落库成功：requestId={}, 订单号={}", requestId, orderNo);
        } catch (BizException ex) {
            // 业务性失败：重投也不会变好 → 回补预扣 + 写终态 + 丢弃
            seckillService.releaseReservation(message.itemId(), message.userId());
            resultStore.save(SeckillGrabResult.failed(requestId, message.userId(), ex.getMessage()));
            queue.discard(delivery);
            log.warn("[秒杀削峰] 落库业务失败，已回补预扣：requestId={}, 原因={}",
                    requestId, ex.getMessage());
        } catch (Exception ex) {
            // 系统性故障：不确认，留在 pending 等回收重投（用户会继续看到「排队中」）
            log.error("[秒杀削峰] 落库异常，等待重投：requestId={}, itemId={}",
                    requestId, message.itemId(), ex);
        }
    }

    private String consumerName() {
        String configured = properties.getAsync().getConsumerName();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        // 多实例部署时应显式配置，否则不同实例可能撞名（撞名会让 pending 归属混淆）
        return "seckill-" + Integer.toHexString(System.identityHashCode(this));
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(ERROR_BACKOFF.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
