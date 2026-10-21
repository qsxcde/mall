package com.geekmall.modules.payment.support;

import com.geekmall.common.constant.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 支付单维度的退款串行锁。
 *
 * <p>同一支付单可能同时收到「部分售后退款」与「整单退款」。两者都需要先读
 * {@code refund_amount} 再判断剩余额度；不串行化时两个请求会读到同一个旧值，
 * 造成超退。锁的粒度是 {@code tradeNo}，只串行同一支付单的退款，不影响其它订单。</p>
 *
 * <p><b>Redis 不可用时 fail-open</b>：退款是资金兜底链路，不能因为锁服务抖动就全部拒绝；
 * 此时退化为原行为（仍有累计金额校验与唯一索引兜底），并记录告警日志。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRefundLock {

    /** 降级令牌：表示没有真正拿到 Redis 锁，解锁时不需要操作 Redis。 */
    public static final String DEGRADED_TOKEN = "__degraded__";

    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """,
            Long.class);

    private static final long RETRY_INTERVAL_MILLIS = 50L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试获取某支付单的退款锁。
     *
     * @return 锁令牌；等待超时返回 {@code null}；Redis 异常返回 {@link #DEGRADED_TOKEN}
     */
    public String tryLock(String tradeNo, Duration ttl, Duration wait) {
        String key = RedisKeys.payRefundLock(tradeNo);
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            while (true) {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
                if (Boolean.TRUE.equals(acquired)) {
                    return token;
                }
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("退款锁不可用，退化为无锁执行：tradeNo={}", tradeNo, e);
            return DEGRADED_TOKEN;
        }
    }

    /** 释放锁；只删除自己写入的令牌，避免误删他人的锁。 */
    public void unlock(String tradeNo, String token) {
        if (token == null || DEGRADED_TOKEN.equals(token)) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(RedisKeys.payRefundLock(tradeNo)), token);
        } catch (Exception e) {
            log.warn("释放退款锁失败（将随 TTL 自动过期）：tradeNo={}", tradeNo, e);
        }
    }
}
