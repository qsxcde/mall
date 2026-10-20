package com.geekmall.modules.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款结果落库（事务边界）。
 *
 * <p>渠道退款调用本身必须在事务外（避免 HTTP 持有数据库行锁），但调用返回后的
 * 「退款单状态 + 支付单累计退款金额/整单退款状态」必须一起成功或一起失败，
 * 否则会出现「退款单显示成功、支付单仍显示退款中」这类对账灾难。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundSettlementService {

    private static final int REFUND_RECORD_DONE = 1;
    private static final int REFUND_RECORD_FAILED = 2;

    private static final int PAYMENT_REFUND_DONE = 2;
    private static final int PAYMENT_REFUND_FAILED = 3;

    private static final int MAX_CONTENT_LENGTH = 1000;

    private final PaymentRefundRecordMapper refundRecordMapper;
    private final PaymentRecordMapper paymentRecordMapper;

    /**
     * 落定一笔退款结果。
     *
     * @param refundId   退款单 ID
     * @param payment    支付单
     * @param result     渠道退款结果
     * @param amount     本次退款金额
     * @param fullRefund 是否把该支付单的剩余金额全部退完
     */
    @Transactional(rollbackFor = Exception.class)
    public void settle(
            Long refundId,
            PaymentRecord payment,
            PaymentChannelClient.RefundResult result,
            BigDecimal amount,
            boolean fullRefund) {
        LocalDateTime now = LocalDateTime.now();

        PaymentRefundRecord update = new PaymentRefundRecord();
        update.setStatus(result.success() ? REFUND_RECORD_DONE : REFUND_RECORD_FAILED);
        update.setChannelRefundNo(result.channelRefundNo());
        update.setCallbackTime(now);
        update.setCallbackContent(cap(result.success() ? result.rawResponse() : result.failReason()));
        // 结算幂等：只有把退款单从「未完成」推进到新状态的那个事务才更新支付单。
        // 这样即使原退款调用与重试任务都拿到渠道成功响应，也只会累加一次金额。
        int rows = refundRecordMapper.update(
                update,
                new LambdaUpdateWrapper<PaymentRefundRecord>()
                        .eq(PaymentRefundRecord::getId, refundId)
                        .ne(PaymentRefundRecord::getStatus, REFUND_RECORD_DONE));
        if (rows == 0) {
            log.info("退款单 {} 已落定，忽略重复结算", refundId);
            return;
        }

        if (result.success()) {
            // 原子累加，避免并发退款时「读 refundAmount → 加 → 覆盖」丢更新
            paymentRecordMapper.addRefundAmount(payment.getId(), amount);
            if (fullRefund) {
                paymentRecordMapper.updateRefundStatus(payment.getId(), PAYMENT_REFUND_DONE);
            }
        } else if (fullRefund) {
            // 只有整单退款失败才需要把整单退款状态置为失败；
            // 部分售后退款失败由退款单自身状态表达，不影响后续整单退款。
            paymentRecordMapper.updateRefundStatus(payment.getId(), PAYMENT_REFUND_FAILED);
        }

        if (result.success()) {
            log.info("支付单 {} 退款成功，本次 ¥{}，渠道退款单号 {}", payment.getTradeNo(), amount, result.channelRefundNo());
        } else {
            log.error("支付单 {} 退款失败：{}", payment.getTradeNo(), result.failReason());
        }
    }

    private String cap(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= MAX_CONTENT_LENGTH ? content : content.substring(0, MAX_CONTENT_LENGTH);
    }
}
