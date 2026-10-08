package com.geekmall.modules.trade.support;

import com.geekmall.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号生成器（Redis 号段模式，多实例安全）。
 *
 * <p>格式：{@code GM + yyyyMMddHHmmss + 10 位全局自增序列}（26 位，兼容 {@code order_no VARCHAR(32)}）。</p>
 *
 * <h3>为什么不能再用「进程内自增 + 随机位」</h3>
 * <p>原实现是「秒内 {@code AtomicLong} 自增 6 位 + 4 位随机位」。自增序列是<b>进程内</b>的，
 * 多实例各自从 0 开始，同一秒不同实例会落到相同的值上，仅靠 4 位随机位区分：
 * 实例越多、并发越高，撞 {@code uk_order_no} 的概率越高，撞上就是 500。</p>
 *
 * <h3>号段模式怎么做到全局唯一</h3>
 * <ol>
 *   <li>用 {@code INCRBY key STEP} 一次性申请一段连续号（默认 1000 个），**返回值是该段的上界**；</li>
 *   <li>Redis 的 {@code INCRBY} 是原子的，因此 N 个实例拿到的是<b>互不重叠</b>的区间；</li>
 *   <li>区间缓存在本地（{@link AtomicLong}），命中期完全不出网，只有耗尽时才再申请一次。</li>
 * </ol>
 * <p>这样既保证了跨实例唯一，又把发号的开销从「一次 Redis 往返」摊薄到「每 STEP 次一次」。</p>
 *
 * <h3>为什么可以依赖 Redis</h3>
 * <p>下单链路本就强依赖 Redis（幂等键 {@code mall:order:idempotent:*} 用 {@code setIfAbsent} 占位），
 * Redis 不可用时下单在幂等这一步就已经失败，因此号段复用同一依赖<b>不引入新的失败模式</b>。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    /**
     * 每次向 Redis 申请的号段大小。
     *
     * <p>取 1000 是在「Redis 往返频率」与「实例崩溃造成的号段空洞」之间折中：
     * 段越大，越少出网，但实例重启会浪费掉未用完的号（只是空洞，不影响唯一性）。</p>
     */
    private static final long STEP = 1000L;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final StringRedisTemplate redisTemplate;

    /** 当前号段内「下一个可用」的号（含）。 */
    private final AtomicLong cursor = new AtomicLong();
    /** 当前号段上界（不含）。{@code cursor >= limit} 表示号段已耗尽，需要申请新段。 */
    private final AtomicLong limit = new AtomicLong();

    /** 生成一个全局唯一的订单号。 */
    public String next() {
        return "GM" + LocalDateTime.now().format(TIMESTAMP) + String.format("%010d", nextSeq());
    }

    private long nextSeq() {
        while (true) {
            long current = cursor.get();
            if (current < limit.get()) {
                // 号段内无锁发号：CAS 失败说明被其他线程抢先，重试即可
                if (cursor.compareAndSet(current, current + 1)) {
                    return current;
                }
                continue;
            }
            // 号段耗尽：进临界区申请新段（synchronized 保证同一时刻只有一个线程出网）
            fetchSegment();
        }
    }

    /**
     * 申请新号段。
     *
     * <p>进入前在锁内二次检查：先到的线程可能已经把 {@code cursor < limit} 填上了，
     * 此时直接返回，避免无谓的第二次 Redis 往返。</p>
     */
    private void fetchSegment() {
        synchronized (this) {
            if (cursor.get() < limit.get()) {
                return;
            }
            Long end = redisTemplate.opsForValue().increment(RedisKeys.ORDER_NO_SEGMENT, STEP);
            if (end == null) {
                // 真实 Redis 不会返回 null；这里防御性失败，避免静默发出重复号
                throw new IllegalStateException("订单号号段申请失败：Redis INCRBY 返回空值");
            }
            limit.set(end);
            cursor.set(end - STEP);
        }
    }
}
