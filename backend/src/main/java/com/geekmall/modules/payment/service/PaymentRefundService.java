package com.geekmall.modules.payment.service;

import com.geekmall.modules.payment.entity.PaymentRecord;
import java.math.BigDecimal;

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

    /**
     * 按售后单退款（支持部分退款）。
     *
     * <p><b>售后退款不能复用「按订单全额退款」</b>，原因有三：</p>
     * <ol>
     *   <li>售后可能只退一部分（商家可调整退款金额），全额退款会把钱多退出去；</li>
     *   <li>同一笔支付单可能先后用于「订单取消退款」与「售后退款」，用订单号做幂等键会互相顶掉；</li>
     *   <li>售后可能发生在订单已完成之后，此时订单本身不可再取消。</li>
     * </ol>
     *
     * <p>因此幂等键是<b>售后单维度</b>：{@code refund_no = "RFAS" + afterSaleNo}，
     * 由 {@code pay_refund_record.uk_refund_no} 唯一索引兜底。
     * 累计退款金额不得超过支付单金额，超出直接拒绝（防资损）。</p>
     *
     * @param orderNo     订单号
     * @param afterSaleNo 售后单号（幂等键的一部分，须全局唯一）
     * @param amount      本次退款金额
     * @param reason      退款原因，落库便于对账
     * @return 本次是否真的发起了退款（false = 未支付 / 金额非法 / 已退过 / 并发中被他人抢到）
     */
    boolean refundForAfterSale(String orderNo, String afterSaleNo, BigDecimal amount, String reason);
}
