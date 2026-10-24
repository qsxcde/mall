package com.geekmall.modules.trade.support;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.modules.trade.dto.SubmitOrderDTO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.service.TradeService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

/**
 * 下单幂等协作类：幂等键构造、占位获取/释放、结果登记、重复支付幂等判定。
 *
 * <p>从 {@code TradeServiceImpl} 拆出的幂等块，方法体逐字搬运、行为不变；
 * 占位获取后的提交编排仍留在交易门面（死锁重试经自代理重开事务）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderIdempotentSupport {

    /**
     * 幂等占位值的<b>前缀</b>。真实值是「前缀 + 本次请求的随机令牌」。
     *
     * <p>令牌的存在是为了失败释放时能判断「这个占位还是不是我的」——
     * 只比对固定常量是不够的：占位 TTL 过期后，另一个同 requestId 的请求会写入
     * <b>同样</b>的常量值，此时裸删除仍会误删他人的占位。</p>
     */
    public static final String IDEMPOTENT_PENDING_PREFIX = "__PENDING__:";

    public static final Duration IDEMPOTENT_LOCK_TTL = Duration.ofMinutes(5);
    public static final Duration IDEMPOTENT_RESULT_TTL = Duration.ofMinutes(30);
    /** 未传 requestId 时的指纹防重窗口（够覆盖用户手抖重复点击） */
    public static final Duration IDEMPOTENT_FP_TTL = Duration.ofSeconds(15);

    /**
     * 幂等键「比对删除」脚本（Lua，原子）。
     *
     * <p><b>为什么不能直接 {@code DEL}</b>：占位键有 5 分钟 TTL。若本次下单执行超过 TTL
     * （例如遭遇长时间锁等待），键已过期并被另一个同 requestId 的请求重新占位；
     * 此时失败清理若是裸 {@code DEL}，就会把<b>他人的占位</b>删掉 ——
     * 幂等防线被从内部拆掉，并发的重复下单会真的产生两张订单。</p>
     *
     * <p>Lua 里「GET 相等才 DEL」保证只清理自己写下的那一个占位。</p>
     */
    private static final RedisScript<Long> RELEASE_IDEMPOTENT_SCRIPT = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /** 幂等键里存的是「结果」（订单号），而不是占位值。 */
    public boolean isIdempotentResult(String value) {
        return value != null && !value.startsWith(IDEMPOTENT_PENDING_PREFIX);
    }

    /**
     * 释放幂等占位（P1-6）：仅当值仍是本请求写入的令牌时才删除。
     *
     * <p>用「比对删除」而不是裸 {@code DEL}，是为了避免占位 TTL 过期后
     * 误删其他请求重新写入的占位（那会让幂等失效、并发出两张订单）。</p>
     */
    public void releaseIdempotentKey(String idempotentKey, String placeholder) {
        if (idempotentKey == null || placeholder == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_IDEMPOTENT_SCRIPT, List.of(idempotentKey), placeholder);
        } catch (Exception ex) {
            // 释放失败不影响本次下单结果：占位会随 TTL 自行过期，用户稍后可重试
            log.warn("释放下单幂等键失败（将由 TTL 兜底）：key={}, error={}", idempotentKey, ex.getMessage());
        }
    }

    /**
     * 构造幂等键（P1-4）。
     *
     * <p>有 requestId 时以它为准；缺失时退化为「用户 + 地址 + 结算项 + 券」指纹，
     * 配合短 TTL 覆盖用户重复点击，而不是像以前那样整段跳过幂等。</p>
     */
    public String buildIdempotentKey(Long userId, SubmitOrderDTO dto) {
        if (dto == null) {
            return null;
        }
        if (StringUtils.hasText(dto.getRequestId())) {
            return RedisKeys.ORDER_IDEMPOTENT + userId + ":" + dto.getRequestId();
        }
        String fingerprint = userId + "|" + dto.getAddressId() + "|" + dto.getCouponId() + "|"
                + (dto.getCartItemIds() == null
                        ? ""
                        : dto.getCartItemIds().stream()
                                .sorted()
                                .map(String::valueOf)
                                .collect(Collectors.joining(",")));
        String hash = DigestUtils.md5DigestAsHex(fingerprint.getBytes(StandardCharsets.UTF_8));
        return RedisKeys.ORDER_IDEMPOTENT + "fp:" + userId + ":" + hash;
    }

    /** 把幂等结果写入放到事务提交之后（失败不影响已提交订单）。 */
    public void registerIdempotentResult(String idempotentKey, String orderNo, Duration resultTtl) {
        if (idempotentKey == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        redisTemplate.opsForValue().set(idempotentKey, orderNo, resultTtl);
                    } catch (Exception e) {
                        log.error("写入下单幂等结果失败：{}", idempotentKey, e);
                    }
                }
            });
        } else {
            redisTemplate.opsForValue().set(idempotentKey, orderNo, resultTtl);
        }
    }

    /**
     * 订单已不在待支付状态时的幂等/重复支付判定。
     *
     * <p>同一个 {@code tradeNo} 重复到达是渠道重试，按幂等成功处理；
     * 不同 {@code tradeNo} 说明用户用另一张支付单又付了一次，必须转退款。</p>
     */
    public TradeService.PaymentApplyResult resolveAlreadyPaid(Order order, String tradeNo) {
        if (StringUtils.hasText(order.getTradeNo()) && !Objects.equals(order.getTradeNo(), tradeNo)) {
            log.warn("订单 {} 已由支付单 {} 落账，收到第二笔支付 {}，判定为重复支付", order.getOrderNo(), order.getTradeNo(), tradeNo);
            return TradeService.PaymentApplyResult.DUPLICATE_PAYMENT;
        }
        return TradeService.PaymentApplyResult.APPLIED;
    }
}
