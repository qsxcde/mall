package com.geekmall.modules.payment.channel;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * 支付渠道抽象。
 *
 * <p>把「下单、查单、关单、退款、验签」收敛到统一接口，使业务代码不绑死具体渠道：
 * 本地渠道（{@code LocalSandboxChannel}）用于零配置启动与自动化测试，
 * 支付宝沙箱（{@code AlipaySandboxChannel}）用于真实联调。</p>
 *
 * <p>实现通过 {@code mall.payment.channel} 选择（见 storage 的 local/minio 双实现范式）。</p>
 */
public interface PaymentChannelClient {

    /** 渠道标识，落库到 {@code pay_payment_record.channel}。 */
    String channelCode();

    /**
     * 预下单，返回可渲染为二维码的内容（支付宝当面付的 {@code qr_code}）。
     *
     * @param outTradeNo 平台支付单号（支付宝 out_trade_no）
     * @param amount     金额
     * @param subject    商品标题
     * @param timeout    支付超时（应取订单剩余时间，而非固定值）
     */
    PrepayResult prepay(String outTradeNo, BigDecimal amount, String subject, Duration timeout);

    /** 主动查单（用于回调丢失时的补偿）。 */
    ChannelTradeState query(String outTradeNo);

    /** 关单（best-effort：本地取消/超时后尽量让渠道侧订单不可再付）。 */
    void close(String outTradeNo);

    /**
     * 退款。<b>支付宝退款是同步模型</b>：结果直接返回，不存在退款专属回调；
     * {@code outRequestNo} 会作为 {@code out_request_no} 交给渠道做二次幂等。
     */
    RefundResult refund(String outTradeNo, String outRequestNo, BigDecimal amount, String reason);

    /**
     * 校验渠道异步通知的签名。
     *
     * <p><b>实现方除验签外，还应校验通知归属本应用</b>（支付宝为 {@code app_id} / {@code seller_id}）——
     * 只验签不足以证明该通知是发给自己的。</p>
     */
    boolean verifyNotify(Map<String, String> params);

    /** 是否支持「模拟付款」（仅本地/演示渠道）。真实渠道应改走 {@link #query} 主动查单。 */
    default boolean supportsSimulatedPayment() {
        return false;
    }

    /**
     * 构造一笔「渠道侧支付成功」的通知参数（含签名），供本地渠道的模拟付款使用。
     * 真实渠道不实现（默认抛出）。
     */
    default Map<String, String> simulatePaidNotify(String outTradeNo, String channelTradeNo, BigDecimal amount) {
        throw new UnsupportedOperationException(channelCode() + " 不支持模拟付款");
    }

    /** 预下单结果。 */
    record PrepayResult(String qrCode) {}

    /** 查单结果。 */
    record ChannelTradeState(String outTradeNo, String channelTradeNo, BigDecimal amount, ChannelTradeStatus status) {}

    /** 退款结果（同步）。 */
    record RefundResult(boolean success, String channelRefundNo, String rawResponse, String failReason) {}

    /** 渠道侧交易状态（各渠道映射到这三个有意义的状态 + 未找到）。 */
    enum ChannelTradeStatus {
        /** 已下单未付款（支付宝 WAIT_BUYER_PAY）。 */
        WAIT_PAY,
        /** 支付成功（支付宝 TRADE_SUCCESS / TRADE_FINISHED）。 */
        SUCCESS,
        /** 已关闭（支付宝 TRADE_CLOSED）。 */
        CLOSED,
        /** 渠道侧查无此单。 */
        NOT_FOUND
    }
}
