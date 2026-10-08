package com.geekmall.modules.marketing.queue;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.modules.marketing.config.SeckillProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis Stream 的秒杀订单队列。
 *
 * <p>选它而不是引入 MQ 的理由：零新增中间件（项目已有 Redis），且 Stream 具备
 * <b>消费者组 + ACK + pending 列表</b> 三件套 —— 这是「可靠队列」的最低要求。
 * 绝不能退化成 {@code LPUSH/BRPOP}：弹出即消失，进程崩溃就丢单，没有 ACK 语义。</p>
 *
 * <p><b>为什么必须有 pending 重投</b>：消费者在「已取出、未 ack」之间崩溃时，
 * 那条消息成了孤儿。若不做 {@link #reclaimStale}，用户会永远停在「排队中」——
 * 这比直接失败更糟，因为他不知道该重试还是该继续等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamSeckillOrderQueue implements SeckillOrderQueue {

    /** 消费者组名：同一条消息在一个组内只会被投递给一个消费者。 */
    private static final String GROUP = "mall:seckill:order:group";

    private final StringRedisTemplate redisTemplate;
    private final SeckillProperties properties;

    /** 组创建只做一次；多实例并发创建会得到 BUSYGROUP，属正常情况。 */
    private volatile boolean groupReady = false;

    @Override
    public void enqueue(SeckillOrderMessage message) {
        ensureGroup();
        long maxLength = properties.getAsync().getMaxStreamLength();
        MapRecord<byte[], byte[], byte[]> record = StreamRecords
                .rawBytes(rawFields(message.toFields()))
                .withStreamKey(rawKey());
        // 用带 MAXLEN 的底层命令：已 ack 的消息不会自动清理，不裁剪会让 Redis 内存无限增长。
        // 近似裁剪（~）比精确裁剪便宜得多，队列场景完全可以接受。
        redisTemplate.execute((RedisCallback<Object>) connection -> connection.streamCommands()
                .xAdd(record, RedisStreamCommands.XAddOptions.maxlen(maxLength).approximateTrimming(true)));
        log.debug("秒杀请求已入队：requestId={}, itemId={}", message.requestId(), message.itemId());
    }

    @Override
    public List<Delivery> poll(String consumer, int maxCount, Duration block) {
        ensureGroup();
        // ">" 表示只读「尚未投递过」的新消息；已被回收（pending）的消息要靠 reclaimStale 取回
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, consumer),
                StreamReadOptions.empty().count(maxCount).block(block),
                StreamOffset.create(RedisKeys.SECKILL_ORDER_STREAM, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Delivery> deliveries = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            try {
                // 首次投递：次数记 1
                deliveries.add(new Delivery(record.getId().getValue(),
                        SeckillOrderMessage.fromFields(record.getValue()), 1L));
            } catch (Exception ex) {
                // 毒消息（字段损坏）：直接丢弃并记录，否则它会被反复重投、永远处理不完
                log.error("秒杀消息解析失败，已丢弃：id={}, fields={}", record.getId(), record.getValue(), ex);
                redisTemplate.opsForStream().acknowledge(RedisKeys.SECKILL_ORDER_STREAM, GROUP, record.getId());
            }
        }
        return deliveries;
    }

    @Override
    public void ack(Delivery delivery) {
        redisTemplate.opsForStream().acknowledge(RedisKeys.SECKILL_ORDER_STREAM, GROUP,
                RecordId.of(delivery.handle()));
    }

    @Override
    public void discard(Delivery delivery) {
        // 与 ack 动作相同，单独方法只为在调用处表达「业务性失败，不再重试」
        ack(delivery);
    }

    @Override
    public List<Delivery> reclaimStale(String consumer, Duration minIdle, int maxCount) {
        ensureGroup();
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(RedisKeys.SECKILL_ORDER_STREAM, GROUP, Range.unbounded(), (long) maxCount);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        // 先记下每条待回收消息「本次 claim 之前」的投递次数；XCLAIM 之后实际次数为 +1。
        // 消费端要靠这个数字判断是否已达上限（转死信），否则故障消息会被无限重投。
        Map<String, Long> deliveredBefore = new LinkedHashMap<>();
        for (PendingMessage item : pending) {
            if (item.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                deliveredBefore.put(item.getId().getValue(), item.getTotalDeliveryCount());
            }
        }
        if (deliveredBefore.isEmpty()) {
            return List.of();
        }
        List<RecordId> stale = deliveredBefore.keySet().stream().map(RecordId::of).toList();
        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                .claim(RedisKeys.SECKILL_ORDER_STREAM, GROUP, consumer, minIdle,
                        stale.toArray(new RecordId[0]));
        if (claimed == null || claimed.isEmpty()) {
            return List.of();
        }
        List<Delivery> deliveries = new ArrayList<>(claimed.size());
        for (MapRecord<String, Object, Object> record : claimed) {
            if (record.getValue() == null || record.getValue().isEmpty()) {
                // 消息体已被 XTRIM 裁掉，无法恢复处理，只能确认掉避免反复占用 pending
                log.warn("回收到的秒杀消息体已不存在（可能被裁剪），丢弃：id={}", record.getId());
                redisTemplate.opsForStream().acknowledge(RedisKeys.SECKILL_ORDER_STREAM, GROUP, record.getId());
                continue;
            }
            long attempts = deliveredBefore.getOrDefault(record.getId().getValue(), 0L) + 1;
            try {
                deliveries.add(new Delivery(record.getId().getValue(),
                        SeckillOrderMessage.fromFields(record.getValue()), attempts));
            } catch (Exception ex) {
                log.error("回收消息解析失败，已丢弃：id={}", record.getId(), ex);
                redisTemplate.opsForStream().acknowledge(RedisKeys.SECKILL_ORDER_STREAM, GROUP, record.getId());
            }
        }
        if (!deliveries.isEmpty()) {
            log.warn("[秒杀队列] 回收了 {} 条超时未确认消息，可能此前有消费者异常退出", deliveries.size());
        }
        return deliveries;
    }

    /* ------------------------------ 运行时快照 ------------------------------ */

    /**
     * 读取队列快照（供指标/健康检查）。
     *
     * <p>三种情况都返回 {@link QueueStats#unavailable()} 而不是抛异常：key 不存在、
     * 消费组尚未创建（{@code NOGROUP}）、Redis 抖动。观测失败绝不能升级为业务失败。</p>
     */
    @Override
    public QueueStats stats() {
        try {
            Long length = redisTemplate.opsForStream().size(RedisKeys.SECKILL_ORDER_STREAM);
            long pending = 0L;
            PendingMessagesSummary summary =
                    redisTemplate.opsForStream().pending(RedisKeys.SECKILL_ORDER_STREAM, GROUP);
            if (summary != null) {
                pending = summary.getTotalPendingMessages();
            }
            long consumers = 0L;
            StreamInfo.XInfoGroups groups =
                    redisTemplate.opsForStream().groups(RedisKeys.SECKILL_ORDER_STREAM);
            if (groups != null) {
                for (StreamInfo.XInfoGroup group : groups) {
                    if (GROUP.equals(group.groupName())) {
                        consumers = group.consumerCount();
                        break;
                    }
                }
            }
            return new QueueStats(length == null ? 0L : length, pending, consumers);
        } catch (Exception ex) {
            log.debug("读取秒杀队列状态失败（按不可用处理）：{}", ex.getMessage());
            return QueueStats.unavailable();
        }
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /**
     * 建消费者组；{@code MKSTREAM} 保证 key 不存在时一并创建，避免启动顺序依赖。
     *
     * <p><b>只在「确实建好」或「已存在」时才置 {@code groupReady}</b>：其它失败（Redis 抖动、
     * 流 key 与消费组被外部删除/淘汰）必须允许下次重试。早期版本无条件置位，
     * 后果是<b>消费组一旦丢失就永久 NOGROUP</b> —— 消费者每秒报错、消息只进不出，
     * 且只能靠重启进程恢复。这类「观测/运维动作把长稳状态打坏」的场景必须能自愈。</p>
     */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        synchronized (this) {
            if (groupReady) {
                return;
            }
            try {
                redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands()
                        .xGroupCreate(rawKey(), GROUP, ReadOffset.from("0"), true));
                log.info("已创建秒杀订单消费者组：{}", GROUP);
                groupReady = true;
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                if (message.contains("BUSYGROUP")) {
                    // 组已存在：本实例重复调用、其它实例先创建，或 key 销毁后组仍在 —— 均属正常
                    log.debug("秒杀消费者组已存在，跳过创建：{}", GROUP);
                    groupReady = true;
                } else {
                    // 不置位 → 下次 enqueue/poll 会重试，避免「组丢了就永久失效」
                    log.warn("创建秒杀消费者组失败，将在下次调用时重试：{}", message);
                }
            }
        }
    }

    private byte[] rawKey() {
        return RedisKeys.SECKILL_ORDER_STREAM.getBytes(StandardCharsets.UTF_8);
    }

    private Map<byte[], byte[]> rawFields(Map<String, String> fields) {
        Map<byte[], byte[]> raw = new LinkedHashMap<>();
        fields.forEach((key, value) ->
                raw.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8)));
        return raw;
    }
}
