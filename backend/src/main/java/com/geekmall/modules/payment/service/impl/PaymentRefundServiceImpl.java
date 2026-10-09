package com.geekmall.modules.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.modules.payment.channel.PaymentChannelClient;
import com.geekmall.modules.payment.entity.PaymentRecord;
import com.geekmall.modules.payment.entity.PaymentRefundRecord;
import com.geekmall.modules.payment.mapper.PaymentRecordMapper;
import com.geekmall.modules.payment.mapper.PaymentRefundRecordMapper;
import com.geekmall.modules.payment.service.PaymentRefundService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 退款编排实现。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：退款要在两次落库之间发起渠道 HTTP 调用，
 * 包在事务里会长时间持锁。本类每一步都是单条语句，各自原子；幂等靠
 * 「refund_status CAS（唯一闸门）+ refund_no 唯一索引」两层。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundServiceImpl implements PaymentRefundService {

    private static final int PAY_STATUS_SUCCESS = 1;

    private static final int REFUND_NONE = 0;
    private static final int REFUND_IN_PROGRESS = 1;
    private static final int REFUND_DONE = 2;
    private static final int REFUND_FAILED = 3;

    private static final int REFUND_RECORD_IN_PROGRESS = 0;
    private static final int REFUND_RECORD_DONE = 1;
    private static final int REFUND_RECORD_FAILED = 2;

    private static final int MAX_CONTENT_LENGTH = 1000;

    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentRefundRecordMapper refundRecordMapper;
    private final PaymentChannelClient paymentChannel;

    @Override
    public boolean refundForClosedOrder(PaymentRecord payment, String reason) {
        if (payment.getStatus() == null || payment.getStatus() != PAY_STATUS_SUCCESS) {
            log.debug("支付单 {} 非支付成功状态，跳过退款", payment.getTradeNo());
            return false;
        }

        // 唯一闸门：refund_status 的 0 → 1 CAS，并发下只有一个调用方抢到
        PaymentRecord claim = new PaymentRecord();
        claim.setRefundStatus(REFUND_IN_PROGRESS);
        int claimed = paymentRecordMapper.update(
                claim,
                new LambdaUpdateWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getId, payment.getId())
                        .eq(PaymentRecord::getRefundStatus, REFUND_NONE));
        if (claimed == 0) {
            log.info("支付单 {} 退款已被（并发地）发起，忽略重复请求", payment.getTradeNo());
            return false;
        }

        // refund_no 确定化（一单一退），唯一索引是第二层幂等
        String refundNo = "RF" + payment.getTradeNo();
        PaymentRefundRecord record;
        try {
            record = insertRefundRecord(payment, refundNo, reason);
        } catch (DuplicateKeyException e) {
            log.warn("退款单 {} 已存在（唯一索引拦截），本次不再发起", refundNo);
            return false;
        }

        // 渠道调用：本方法无事务，此处不会持有数据库行锁
        PaymentChannelClient.RefundResult result =
                paymentChannel.refund(payment.getTradeNo(), refundNo, payment.getAmount(), reason);

        finishRefund(record.getId(), payment, result);
        return true;
    }

    @Override
    public boolean refundIfPaid(String orderNo, String reason) {
        PaymentRecord payment = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .eq(PaymentRecord::getStatus, PAY_STATUS_SUCCESS)
                .orderByDesc(PaymentRecord::getId)
                .last("limit 1"));
        if (payment == null) {
            return false;
        }
        return refundForClosedOrder(payment, reason);
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private PaymentRefundRecord insertRefundRecord(PaymentRecord payment, String refundNo, String reason) {
        PaymentRefundRecord record = new PaymentRefundRecord();
        record.setRefundNo(refundNo);
        record.setTradeNo(payment.getTradeNo());
        record.setOrderNo(payment.getOrderNo());
        record.setUserId(payment.getUserId());
        record.setChannel(payment.getChannel());
        record.setAmount(payment.getAmount());
        record.setStatus(REFUND_RECORD_IN_PROGRESS);
        record.setReason(reason);
        refundRecordMapper.insert(record);
        return record;
    }

    private void finishRefund(Long refundId, PaymentRecord payment, PaymentChannelClient.RefundResult result) {
        LocalDateTime now = LocalDateTime.now();

        PaymentRefundRecord update = new PaymentRefundRecord();
        update.setId(refundId);
        update.setStatus(result.success() ? REFUND_RECORD_DONE : REFUND_RECORD_FAILED);
        update.setChannelRefundNo(result.channelRefundNo());
        update.setCallbackTime(now);
        update.setCallbackContent(cap(result.success() ? result.rawResponse() : result.failReason()));
        refundRecordMapper.updateById(update);

        PaymentRecord paymentUpdate = new PaymentRecord();
        paymentUpdate.setId(payment.getId());
        paymentUpdate.setRefundStatus(result.success() ? REFUND_DONE : REFUND_FAILED);
        paymentUpdate.setRefundAmount(result.success() ? payment.getAmount() : BigDecimal.ZERO);
        paymentRecordMapper.updateById(paymentUpdate);

        if (result.success()) {
            log.info("支付单 {} 退款成功，渠道退款单号 {}", payment.getTradeNo(), result.channelRefundNo());
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
