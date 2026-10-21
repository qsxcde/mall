package com.geekmall.modules.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.config.PaymentProperties;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import com.geekmall.modules.payment.service.PaymentRefundService;
import com.geekmall.modules.payment.support.PaymentRefundLock;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 退款编排实现。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：退款要在两次落库之间发起渠道 HTTP 调用，
 * 包在事务里会长时间持锁。渠道调用本身在事务外；调用返回后的状态落库交给
 * {@link PaymentRefundSettlementService} 在一个短事务里完成。</p>
 *
 * <p><b>{@code refund_status} 只表达「整单退款」状态</b>：部分售后退款只累加
 * {@code refund_amount}，不修改该字段。否则一次部分退款就会把后续整单退款的
 * CAS 闸门永久关上。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundServiceImpl implements PaymentRefundService {

    private static final int PAY_STATUS_SUCCESS = 1;

    private static final int REFUND_NONE = 0;
    private static final int REFUND_IN_PROGRESS = 1;

    private static final int REFUND_RECORD_IN_PROGRESS = 0;

    /** 首次插入退款单后的保护窗口：避免重试任务与原退款调用并发执行。 */
    private static final long INITIAL_RETRY_DELAY_MINUTES = 5;

    /** 重试退避阶梯（秒）：1m / 5m / 15m / 1h / 6h。 */
    private static final List<Long> RETRY_BACKOFF_SECONDS = List.of(60L, 300L, 900L, 3_600L, 21_600L);

    private static final Duration REFUND_LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration REFUND_LOCK_WAIT = Duration.ofSeconds(2);

    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentRefundRecordMapper refundRecordMapper;
    private final PaymentChannelClient paymentChannel;
    private final PaymentRefundSettlementService settlementService;
    private final PaymentProperties paymentProperties;
    private final MeterRegistry meterRegistry;
    /** 同一支付单的退款串行锁：避免部分售后与整单退款并发读旧额度导致超退。 */
    private final PaymentRefundLock refundLock;

    @Override
    public boolean refundForClosedOrder(PaymentRecord payment, String reason) {
        // 订单关闭与重复支付都是「这笔钱不应保留」，统一按剩余金额全额退款
        return refundFull(payment, reason);
    }

    @Override
    public boolean refundFull(PaymentRecord payment, String reason) {
        if (!isSuccessful(payment)) {
            log.debug("支付单非支付成功状态，跳过退款：{}", payment == null ? null : payment.getTradeNo());
            return false;
        }
        String lock = refundLock.tryLock(payment.getTradeNo(), REFUND_LOCK_TTL, REFUND_LOCK_WAIT);
        if (lock == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "退款处理中，请稍后重试");
        }
        try {
            // 拿到锁后重新读取，确保基于其它退款提交后的最新累计金额计算
            PaymentRecord current = paymentRecordMapper.selectById(payment.getId());
            if (!isSuccessful(current)) {
                return false;
            }
            return doRefundFull(current, reason);
        } finally {
            refundLock.unlock(payment.getTradeNo(), lock);
        }
    }

    private boolean doRefundFull(PaymentRecord payment, String reason) {
        BigDecimal paid = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
        BigDecimal refunded = payment.getRefundAmount() == null ? BigDecimal.ZERO : payment.getRefundAmount();
        BigDecimal remaining = paid.subtract(refunded);
        if (remaining.signum() <= 0) {
            log.info("支付单 {} 已全额退款（已退 ¥{}），跳过", payment.getTradeNo(), refunded);
            return false;
        }

        // 唯一闸门：整单退款 status 的 0 → 1 CAS，并发下只有一个调用方抢到。
        // 部分售后退款不会修改该字段，因此这里仍能正常抢到。
        PaymentRecord claim = new PaymentRecord();
        claim.setRefundStatus(REFUND_IN_PROGRESS);
        int claimed = paymentRecordMapper.update(
                claim,
                new LambdaUpdateWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getId, payment.getId())
                        .eq(PaymentRecord::getRefundStatus, REFUND_NONE));
        if (claimed == 0) {
            log.info("支付单 {} 整单退款已被（并发地）发起，忽略重复请求", payment.getTradeNo());
            return false;
        }

        // refund_no 确定化（一单一退），唯一索引是第二层幂等
        String refundNo = "RF" + payment.getTradeNo();
        PaymentRefundRecord record;
        try {
            record = insertRefundRecord(payment, refundNo, reason, remaining, true);
        } catch (DuplicateKeyException e) {
            log.warn("退款单 {} 已存在（唯一索引拦截），本次不再发起", refundNo);
            return false;
        }

        // 渠道调用：本方法无事务，此处不会持有数据库行锁
        PaymentChannelClient.RefundResult result =
                paymentChannel.refund(payment.getTradeNo(), refundNo, remaining, reason);

        settlementService.settle(record.getId(), payment, result, remaining, true);
        return true;
    }

    @Override
    public boolean refundIfPaid(String orderNo, String reason) {
        // 一个订单理论上只有一笔成功支付；出现多笔说明历史上已发生重复支付，
        // 这里逐笔退款（每笔都有 refund_status CAS 与 refund_no 唯一索引兜底）。
        List<PaymentRecord> payments = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .eq(PaymentRecord::getStatus, PAY_STATUS_SUCCESS)
                .orderByDesc(PaymentRecord::getId));
        boolean refunded = false;
        for (PaymentRecord payment : payments) {
            refunded |= refundFull(payment, reason);
        }
        return refunded;
    }

    /**
     * 售后按金额退款。
     *
     * <p>幂等键是<b>售后单</b>而不是支付单：同一笔支付可能先经历「订单取消退款」，
     * 再进入售后退款；若沿用 {@code RF + tradeNo}，第二笔会被唯一索引直接顶掉。
     * 这里改用累计金额校验 + {@code RFAS + afterSaleNo} 唯一索引双层防超退。</p>
     *
     * <p>部分退款不修改整单 {@code refund_status}；只有本次恰好退完剩余金额时才标记为
     * 整单退款完成。</p>
     */
    @Override
    public boolean refundForAfterSale(String orderNo, String afterSaleNo, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            log.warn("售后单 {} 退款金额非法（{}），跳过", afterSaleNo, amount);
            return false;
        }
        PaymentRecord payment = findSuccessfulPayment(orderNo);
        if (payment == null) {
            log.info("订单 {} 无支付成功的支付单，售后 {} 无需退款", orderNo, afterSaleNo);
            return false;
        }
        String lock = refundLock.tryLock(payment.getTradeNo(), REFUND_LOCK_TTL, REFUND_LOCK_WAIT);
        if (lock == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "退款处理中，请稍后重试");
        }
        try {
            PaymentRecord current = paymentRecordMapper.selectById(payment.getId());
            if (!isSuccessful(current)) {
                return false;
            }
            return doRefundForAfterSale(current, afterSaleNo, amount, reason);
        } finally {
            refundLock.unlock(payment.getTradeNo(), lock);
        }
    }

    private boolean doRefundForAfterSale(PaymentRecord payment, String afterSaleNo, BigDecimal amount, String reason) {
        BigDecimal paid = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
        BigDecimal refunded = payment.getRefundAmount() == null ? BigDecimal.ZERO : payment.getRefundAmount();
        BigDecimal remaining = paid.subtract(refunded);
        if (amount.compareTo(remaining) > 0) {
            log.error("售后 {} 退款超限：已退 ¥{} + 本次 ¥{} > 支付 ¥{}，拒绝", afterSaleNo, refunded, amount, paid);
            return false;
        }
        boolean fullRefund = amount.compareTo(remaining) == 0;

        String refundNo = "RFAS" + afterSaleNo;
        PaymentRefundRecord record;
        try {
            record = insertRefundRecord(payment, refundNo, reason, amount, fullRefund);
        } catch (DuplicateKeyException e) {
            log.info("售后 {} 的退款单已存在（并发重复提交），本次不再发起", afterSaleNo);
            return false;
        }

        // 渠道调用：无事务包裹，不持有数据库行锁
        PaymentChannelClient.RefundResult result =
                paymentChannel.refund(payment.getTradeNo(), refundNo, amount, reason);

        settlementService.settle(record.getId(), payment, result, amount, fullRefund);
        return true;
    }

    @Override
    public int retryFailedRefunds(int batchSize) {
        int limit = Math.max(1, batchSize);
        int maxRetry = Math.max(1, paymentProperties.getRefundRetry().getMaxRetry());
        List<PaymentRefundRecord> candidates = refundRecordMapper.selectRetryable(maxRetry, limit);
        int retried = 0;
        for (PaymentRefundRecord record : candidates) {
            int expected = record.getRetryCount() == null ? 0 : record.getRetryCount();
            LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(backoffSeconds(expected + 1));
            // 乐观锁抢占：多实例/并发扫描时只有一个执行者能推进 retry_count
            if (refundRecordMapper.claimRetry(record.getId(), expected, nextRetry) == 0) {
                continue;
            }

            String lock = refundLock.tryLock(record.getTradeNo(), REFUND_LOCK_TTL, REFUND_LOCK_WAIT);
            if (lock == null) {
                log.info("退款重试锁被占用，本轮跳过：refundNo={}", record.getRefundNo());
                continue;
            }
            try {
                PaymentRecord payment = findPaymentByTradeNo(record.getTradeNo());
                if (!isSuccessful(payment)) {
                    log.error(
                            "退款重试跳过：支付单不存在或非支付成功状态，refundNo={}, tradeNo={}", record.getRefundNo(), record.getTradeNo());
                    continue;
                }

                try {
                    PaymentChannelClient.RefundResult result = paymentChannel.refund(
                            record.getTradeNo(), record.getRefundNo(), record.getAmount(), record.getReason());
                    settlementService.settle(record.getId(), payment, result, record.getAmount(), isFullRefund(record));
                    if (result.success()) {
                        retried++;
                    } else if (expected + 1 >= maxRetry) {
                        recordRetryExhausted(record, maxRetry);
                    }
                } catch (Exception e) {
                    if (expected + 1 >= maxRetry) {
                        recordRetryExhausted(record, maxRetry);
                    }
                    // next_retry_time 已在 claim 时写入；本次失败等下一轮，达到 maxRetry 后不再被选中
                    log.error("退款重试失败：refundNo={}，下次重试时间={}", record.getRefundNo(), nextRetry, e);
                }
            } finally {
                refundLock.unlock(record.getTradeNo(), lock);
            }
        }
        if (retried > 0) {
            log.info("[退款重试] 本轮成功落定 {} 笔（本批扫描 {} 笔）", retried, candidates.size());
        }
        return retried;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /** 重试退避：第 1 次 1 分钟，之后逐步拉长，最长 6 小时。 */
    private long backoffSeconds(int retryNumber) {
        int index = Math.min(Math.max(1, retryNumber), RETRY_BACKOFF_SECONDS.size()) - 1;
        return RETRY_BACKOFF_SECONDS.get(index);
    }

    private void recordRetryExhausted(PaymentRefundRecord record, int maxRetry) {
        meterRegistry.counter("mall_payment_refund_retry_exhausted_total").increment();
        log.error(
                "退款重试达到上限（{} 次），需人工处理：refundNo={}, tradeNo={}, amount={}",
                maxRetry,
                record.getRefundNo(),
                record.getTradeNo(),
                record.getAmount());
    }

    /**
     * 是否为整单退款。优先读 {@code full_refund}，历史数据按单号前缀兜底：
     * 整单退款为 {@code RF<tradeNo>}，售后退款为 {@code RFAS<afterSaleNo>}。
     */
    private boolean isFullRefund(PaymentRefundRecord record) {
        if (record.getFullRefund() != null && record.getFullRefund() == 1) {
            return true;
        }
        return record.getRefundNo() != null
                && record.getRefundNo().startsWith("RF")
                && !record.getRefundNo().startsWith("RFAS");
    }

    private PaymentRecord findPaymentByTradeNo(String tradeNo) {
        return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getTradeNo, tradeNo)
                .last("limit 1"));
    }

    private boolean isSuccessful(PaymentRecord payment) {
        return payment != null && payment.getStatus() != null && payment.getStatus() == PAY_STATUS_SUCCESS;
    }

    private PaymentRecord findSuccessfulPayment(String orderNo) {
        return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .eq(PaymentRecord::getStatus, PAY_STATUS_SUCCESS)
                .orderByDesc(PaymentRecord::getId)
                .last("limit 1"));
    }

    private PaymentRefundRecord insertRefundRecord(
            PaymentRecord payment, String refundNo, String reason, BigDecimal amount, boolean fullRefund) {
        PaymentRefundRecord record = new PaymentRefundRecord();
        record.setRefundNo(refundNo);
        record.setTradeNo(payment.getTradeNo());
        record.setOrderNo(payment.getOrderNo());
        record.setUserId(payment.getUserId());
        record.setChannel(payment.getChannel());
        record.setAmount(amount);
        record.setStatus(REFUND_RECORD_IN_PROGRESS);
        record.setRetryCount(0);
        record.setFullRefund(fullRefund ? 1 : 0);
        // 首次调用有保护窗口：避免重试任务在「渠道已调用、结算尚未落库」时并发拾取同一条
        record.setNextRetryTime(LocalDateTime.now().plusMinutes(INITIAL_RETRY_DELAY_MINUTES));
        record.setReason(reason);
        refundRecordMapper.insert(record);
        return record;
    }
}
