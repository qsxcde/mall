package com.geekmall.modules.payment.channel;

import com.geekmall.modules.payment.support.AlipaySigner;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地沙箱渠道：零配置启动与自动化测试用（默认渠道）。
 *
 * <p>它在进程内自生成一对 RSA 密钥，并用同一套 {@link AlipaySigner}（RSA2）签名/验签 ——
 * 因此「验签 → 幂等 → 金额校验 → 关单转退款」这条链路在本地是被<b>真实执行</b>的，
 * 只是不产生任何网络请求与真实资金。</p>
 *
 * <p>提供 {@link #signNotify} / {@link #markPaid} 两个测试接缝，供集成测试构造
 * 「一份真实签名形态的通知」与「渠道侧已支付」状态。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mall.payment.channel", havingValue = "local", matchIfMissing = true)
public class LocalSandboxChannel implements PaymentChannelClient {

    private static final String CHANNEL = "local";
    private static final String CHARSET = "utf-8";

    private final String privateKeyBase64;
    private final String publicKeyBase64;

    /** 测试接缝：记录「渠道侧已支付」的单号，供 {@link #query} 返回 SUCCESS。 */
    private final Map<String, ChannelTradeState> paidTrades = new ConcurrentHashMap<>();

    public LocalSandboxChannel() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            this.privateKeyBase64 =
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            this.publicKeyBase64 =
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            log.info("[支付] 已启用本地沙箱渠道（自签自发，不产生真实资金）");
        } catch (Exception e) {
            throw new IllegalStateException("本地沙箱渠道初始化失败", e);
        }
    }

    @Override
    public String channelCode() {
        return CHANNEL;
    }

    @Override
    public PrepayResult prepay(String outTradeNo, BigDecimal amount, String subject, Duration timeout) {
        // 真实渠道返回可扫的 qr_code；本地给一个自描述串，前端照样渲染成二维码
        String qrCode =
                "local://pay?outTradeNo=" + outTradeNo + "&amount=" + amount.toPlainString() + "&subject=" + subject;
        log.info("[支付·本地渠道] 预下单 {} 金额 ¥{} 超时 {}", outTradeNo, amount, timeout);
        return new PrepayResult(qrCode);
    }

    @Override
    public ChannelTradeState query(String outTradeNo) {
        ChannelTradeState state = paidTrades.get(outTradeNo);
        if (state != null) {
            return state;
        }
        // 未标记为已支付的单：渠道侧处于「已下单未付款」
        return new ChannelTradeState(outTradeNo, null, null, ChannelTradeStatus.WAIT_PAY);
    }

    @Override
    public void close(String outTradeNo) {
        log.info("[支付·本地渠道] 关单 {}", outTradeNo);
    }

    @Override
    public RefundResult refund(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        String channelRefundNo = "LOCALRF" + outRequestNo;
        log.info("[支付·本地渠道] 退款 {} 金额 ¥{} 原因 {} → {}", outTradeNo, amount, reason, channelRefundNo);
        return new RefundResult(
                true, channelRefundNo, "{\"local\":true,\"refundNo\":\"" + channelRefundNo + "\"}", null);
    }

    @Override
    public boolean verifyNotify(Map<String, String> params) {
        return AlipaySigner.verify(params, publicKeyBase64, params.getOrDefault("charset", CHARSET));
    }

    @Override
    public boolean supportsSimulatedPayment() {
        return true;
    }

    @Override
    public Map<String, String> simulatePaidNotify(String outTradeNo, String channelTradeNo, BigDecimal amount) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", channelTradeNo);
        params.put("total_amount", amount.toPlainString());
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("gmt_payment", LocalDateTime.now().toString());
        return signNotify(params);
    }

    /* ------------------------------ 测试接缝 ------------------------------ */

    /**
     * 用本渠道的私钥给通知参数签名（模拟支付宝服务器发通知）。
     *
     * @return 可直接投递给回调端点的参数（含 sign / sign_type）
     */
    public Map<String, String> signNotify(Map<String, String> params) {
        Map<String, String> signed = new LinkedHashMap<>(params);
        signed.putIfAbsent("sign_type", "RSA2");
        signed.putIfAbsent("charset", CHARSET);
        signed.put("sign", AlipaySigner.sign(signed, privateKeyBase64, CHARSET));
        return signed;
    }

    /** 标记渠道侧「已支付」，供主动查单补偿链路测试。 */
    public void markPaid(String outTradeNo, String channelTradeNo, BigDecimal amount) {
        paidTrades.put(
                outTradeNo, new ChannelTradeState(outTradeNo, channelTradeNo, amount, ChannelTradeStatus.SUCCESS));
    }
}
