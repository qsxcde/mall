package com.geekmall.modules.payment.service;

import com.geekmall.modules.payment.entity.PaymentRecord;

/**
 * 退款编排。
 *
 * <p>支付宝退款是<b>同步模型</b>：结果在 {@code alipay.trade.refund} 的 HTTP 响应里，
 * 不存在退款专属回调。因此这里不做事务包裹 —— 每一步都是单条语句（各自原子），
 * 渠道调用刻意放在两次落库之间，避免在事务里发起网络请求。</p>
 *
 * <p>幂等由两层保证：{@code refund_no} 唯一索引 + {@code pay_payment_record.refund_status} 的 CAS。</p>
 */
public interface PaymentRefundService {

    /**
     * 对已支付的支付单发起全额退款（幂等，可重复调用）。
     *
     * @param payment 支付单（须为支付成功状态，否则直接返回）
     * @param reason  退款原因，落库便于对账
     * @return 本次是否真的发起了退款（false = 未支付成功 / 已退过 / 并发中被他人抢到）
     */
    boolean refundForClosedOrder(PaymentRecord payment, String reason);

    /**
     * 按订单号退款：仅当该订单存在「支付成功且未退款」的支付单时才动作。
     *
     * <p>供交易域在「取消 / 关闭一笔已支付订单」后调用；未支付的订单是 no-op。</p>
     *
     * @return 本次是否真的发起了退款
     */
    boolean refundIfPaid(String orderNo, String reason);
}
