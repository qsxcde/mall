package com.geekmall.modules.payment.support;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 渠道异步通知的归一化视图（当前按支付宝字段名解析）。
 *
 * <p><b>注意 {@code trade_status} 只有 4 个取值</b>：
 * {@code WAIT_BUYER_PAY}（中间态，未付款）、{@code TRADE_SUCCESS}、{@code TRADE_FINISHED}、
 * {@code TRADE_CLOSED}。<b>没有「支付失败」这个通知态</b> —— 把非成功一律当失败会误伤
 * {@code WAIT_BUYER_PAY}。</p>
 *
 * @param outTradeNo    平台支付单号（支付宝 out_trade_no）
 * @param channelTradeNo 渠道交易号（支付宝 trade_no）
 * @param amount        通知金额（可能为 null 或非法格式）
 * @param tradeStatus   交易状态
 * @param appId         通知里的应用 ID，用于校验通知归属
 * @param sellerId      通知里的收款方 ID，用于校验通知归属
 * @param charset       通知声明的字符集（验签解码需按它，而非硬编码 UTF-8）
 * @param raw           原始参数（留痕用）
 */
public record ChannelNotify(
        String outTradeNo,
        String channelTradeNo,
        BigDecimal amount,
        String tradeStatus,
        String appId,
        String sellerId,
        String charset,
        Map<String, String> raw) {

    public static final String STATUS_WAIT_BUYER_PAY = "WAIT_BUYER_PAY";
    public static final String STATUS_SUCCESS = "TRADE_SUCCESS";
    public static final String STATUS_FINISHED = "TRADE_FINISHED";
    public static final String STATUS_CLOSED = "TRADE_CLOSED";

    /** 从原始表单参数解析；金额非法时置 null，由调用方按「信息缺失」处理。 */
    public static ChannelNotify parse(Map<String, String> params) {
        return new ChannelNotify(
                params.get("out_trade_no"),
                params.get("trade_no"),
                parseAmount(params.get("total_amount")),
                params.get("trade_status"),
                params.get("app_id"),
                params.get("seller_id"),
                params.getOrDefault("charset", "utf-8"),
                params);
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 支付成功（当面付付款完成后为 TRADE_SUCCESS）。 */
    public boolean isPaid() {
        return STATUS_SUCCESS.equals(tradeStatus) || STATUS_FINISHED.equals(tradeStatus);
    }

    /** 交易关闭（未付款超时关闭 / 全额退款后关闭）。 */
    public boolean isClosed() {
        return STATUS_CLOSED.equals(tradeStatus);
    }

    /** 已下单未付款的中间态：不改状态，但要应答 success，否则渠道会一直重试。 */
    public boolean isWaitBuyerPay() {
        return STATUS_WAIT_BUYER_PAY.equals(tradeStatus);
    }
}
