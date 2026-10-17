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

    /**
     * 售后按金额退款。
     *
     * <p>幂等键是<b>售后单</b>而不是支付单：同一笔支付可能先经历「订单取消退款」，
     * 再进入售后退款；若沿用 {@code RF + tradeNo}，第二笔会被唯一索引直接顶掉，
     * 结果是「售后显示已退款，钱却没出去」。</p>
     *
     * <p>不做支付单维度的 {@code refund_status} CAS —— 那是「整单一次性」的闸门，
     * 这里改由累计金额校验 + refund_no 唯一索引双层保证不超退。</p>
     */
    @Override
    public boolean refundForAfterSale(String orderNo, String afterSaleNo, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            log.warn("售后单 {} 退款金额非法（{}），跳过", afterSaleNo, amount);
            return false;
        }
        PaymentRecord payment = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderNo, orderNo)
                .eq(PaymentRecord::getStatus, PAY_STATUS_SUCCESS)
                .orderByDesc(PaymentRecord::getId)
                .last("limit 1"));
        if (payment == null) {
            log.info("订单 {} 无支付成功的支付单，售后 {} 无需退款", orderNo, afterSaleNo);
            return false;
        }

        // 防超退：本次 + 历史已退 不得超过支付金额
        BigDecimal refunded = payment.getRefundAmount() == null ? BigDecimal.ZERO : payment.getRefundAmount();
        if (refunded.add(amount).compareTo(payment.getAmount()) > 0) {
            log.error("售后 {} 退款超限：已退 ¥{} + 本次 ¥{} > 支付 ¥{}，拒绝", afterSaleNo, refunded, amount, payment.getAmount());
            return false;
        }

        String refundNo = "RFAS" + afterSaleNo;
        PaymentRefundRecord record;
        try {
            record = insertRefundRecord(payment, refundNo, reason, amount);
        } catch (DuplicateKeyException e) {
            log.info("售后 {} 的退款单已存在（并发重复提交），本次不再发起", afterSaleNo);
            return false;
        }

        // 渠道调用：无事务包裹，不持有数据库行锁
        PaymentChannelClient.RefundResult result =
                paymentChannel.refund(payment.getTradeNo(), refundNo, amount, reason);

        finishRefund(record.getId(), payment, result, amount);
        return true;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private PaymentRefundRecord insertRefundRecord(PaymentRecord payment, String refundNo, String reason) {
        // 全额退款（订单关闭转退款）：金额恒等于支付金额
        return insertRefundRecord(payment, refundNo, reason, payment.getAmount());
    }

    private PaymentRefundRecord insertRefundRecord(
            PaymentRecord payment, String refundNo, String reason, BigDecimal amount) {
        PaymentRefundRecord record = new PaymentRefundRecord();
        record.setRefundNo(refundNo);
        record.setTradeNo(payment.getTradeNo());
        record.setOrderNo(payment.getOrderNo());
        record.setUserId(payment.getUserId());
        record.setChannel(payment.getChannel());
        record.setAmount(amount);
        record.setStatus(REFUND_RECORD_IN_PROGRESS);
        record.setReason(reason);
        refundRecordMapper.insert(record);
        return record;
    }

    private void finishRefund(Long refundId, PaymentRecord payment, PaymentChannelClient.RefundResult result) {
        finishRefund(refundId, payment, result, payment.getAmount());
    }

    /**
     * 落定退款结果。
     *
     * @param amount 本次退款金额。支付单上的 {@code refund_amount} 语义是<b>累计</b>已退金额，
     *               因此这里累加而不是覆盖 —— 订单全额退款之后再发生部分售后退款时，
     *               覆盖写会把前一次的金额抹掉，对账时会出现「退款单合计 ≠ 支付单已退」。
     */
    private void finishRefund(
            Long refundId, PaymentRecord payment, PaymentChannelClient.RefundResult result, BigDecimal amount) {
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
        if (result.success()) {
            BigDecimal already = payment.getRefundAmount() == null ? BigDecimal.ZERO : payment.getRefundAmount();
            paymentUpdate.setRefundAmount(already.add(amount));
        }
        paymentRecordMapper.updateById(paymentUpdate);

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
