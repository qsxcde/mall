package com.geekmall.modules.marketing.queue;

import java.time.Duration;
import java.util.List;

/**
 * 秒杀待落库订单队列（削峰的核心抽象）。
 *
 * <p>刻意做成接口而不是直接写 Redis Stream 调用，原因有两个：</p>
 * <ol>
 *   <li><b>可替换</b>：将来量级上来要换 RocketMQ / Kafka 时，只换实现类，
 *       {@code SeckillServiceImpl} 与消费者代码不动；</li>
 *   <li><b>可测试</b>：单测可以用内存实现验证消费端的幂等与补偿逻辑，
 *       不必依赖真实 Redis Stream。</li>
 * </ol>
 *
 * <p><b>语义约定</b>（换成任何 MQ 都应成立）：</p>
 * <ul>
 *   <li>投递是<b>至少一次</b>语义 —— 消费端必须自己幂等；</li>
 *   <li>消息被取出后进入「未确认」状态，只有 {@link #ack} / {@link #discard} 之后才算处理完；</li>
 *   <li>消费端崩溃（未确认）的消息必须能被 {@link #reclaimStale} 回收重投，
 *       否则那批请求会永远停在「排队中」—— 这正是异步化最容易被忽略的失败路径。</li>
 * </ul>
 */
public interface SeckillOrderQueue {

    /**
     * 投递一条待落库的抢购请求。
     *
     * <p>只应在 Redis Lua 预扣成功之后调用 —— 队列里应当只有「已经拿到资格」的请求，
     * 这样队列长度天然有界（不超过库存数），不会被无效请求灌爆。</p>
     */
    void enqueue(SeckillOrderMessage message);

    /**
     * 拉取一批待处理的新消息。
     *
     * @param consumer 消费者标识（同一消费者组内的实例名）
     * @param maxCount 单次最多拉取条数
     * @param block    无消息时的最长阻塞等待时间
     */
    List<Delivery> poll(String consumer, int maxCount, Duration block);

    /** 处理成功：确认消息，之后不会再被投递。 */
    void ack(Delivery delivery);

    /**
     * 处理失败但**不应重试**（业务性失败，如库存已扣光、重复抢购、消息体损坏）。
     *
     * <p>与 {@link #ack} 的实际效果相同，单独一个方法只是为了在调用处显式表达
     * 「这条消息到此为止」的语义，避免把「不需要重试」写成普通 ack 而丢失故障信号。</p>
     */
    void discard(Delivery delivery);

    /**
     * 回收长时间未确认的消息并管辖到当前消费者名下。
     *
     * <p>返回的记录**已经带着消息体**，调用方应直接处理它们（而不是等下一次
     * {@link #poll} —— 后者只读新消息，读不到这些被回收的）。消息体已被裁剪掉的
     * 记录会被自动丢弃。</p>
     *
     * @return 可处理的已回收消息
     */
    List<Delivery> reclaimStale(String consumer, Duration minIdle, int maxCount);

    /**
     * 队列运行时快照，供指标采集与健康检查使用。
     *
     * <p><b>采集失败不得抛异常</b>：观测能力本身故障（Redis 抖动、消费组尚未创建）
     * 不应该影响业务，也不应该让 Prometheus 抓取失败 —— 调用方应返回
     * {@link QueueStats#unavailable()} 这个哨兵值表达「取不到」。</p>
     */
    QueueStats stats();

    /**
     * 队列快照。
     *
     * @param length    流中条目总数（{@code XLEN}，含已确认但尚未被裁剪的）
     * @param pending   已投递但<b>未确认</b>的条目数（{@code XPENDING}）——
     *                  这才是真正意义上的「在途积压」：消费者卡住时它会持续上涨
     * @param consumers 组内活跃消费者数（{@code XINFO GROUPS}）
     */
    record QueueStats(long length, long pending, long consumers) {

        /** 取不到时的哨兵值（各字段为 -1），避免用 0 冒充「队列很空」。 */
        public static QueueStats unavailable() {
            return new QueueStats(-1L, -1L, -1L);
        }

        public boolean available() {
            return length >= 0;
        }
    }

    /**
     * 一条已取出的消息及其确认句柄。
     *
     * @param handle     确认句柄（Redis Stream 的 record id）
     * @param message    消息体
     * @param deliveries 该消息的<b>累计投递次数</b>：首次投递为 1，每次回收重投 +1。
     *                   消费端据此判断是否已达上限，从而转入死信而不是无限重投；
     *                   换成任何 MQ 都应能提供等价语义（重投计数 / redelivery flag）。
     */
    record Delivery(String handle, SeckillOrderMessage message, long deliveries) {
    }
}
