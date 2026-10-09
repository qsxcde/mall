package com.geekmall.modules.payment.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.resilience.ResilienceGuard;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.payment.config.PaymentProperties;
import com.geekmall.modules.payment.support.AlipaySigner;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 支付宝沙箱渠道（当面付）。
 *
 * <p>用 JDK 原生 {@link HttpClient} 调网关、{@link AlipaySigner} 做 RSA2 签名，
 * <b>不引入官方 SDK</b>。仅当 {@code mall.payment.channel=alipay} 时注册。</p>
 *
 * <p>接口对应关系：预下单 {@code alipay.trade.precreate}、查单 {@code alipay.trade.query}、
 * 关单 {@code alipay.trade.close}、退款 {@code alipay.trade.refund}。</p>
 *
 * <p>外部调用统一包在 {@link ResilienceGuard} 的 {@code alipay} 资源下：
 * 网关变慢或不可用时尽快熔断，避免拖垮 Tomcat 线程。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mall.payment.channel", havingValue = "alipay")
@RequiredArgsConstructor
public class AlipaySandboxChannel implements PaymentChannelClient {

    private static final String CHANNEL = "alipay";
    private static final String RESOURCE = "alipay";
    private static final String CODE_SUCCESS = "10000";
    /** 交易不存在：主动查单遇到它属于正常情况（渠道侧还没这单）。 */
    private static final String SUB_CODE_TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";
    /** timeout_express 上限 15 天。 */
    private static final long MAX_TIMEOUT_MINUTES = 15 * 24 * 60L;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final ResilienceGuard resilienceGuard;

    private volatile HttpClient httpClient;

    /** 密钥不齐时启动即失败，好过用户点了支付才发现。 */
    @PostConstruct
    void validateConfiguration() {
        if (!properties.getAlipay().ready()) {
            throw new IllegalStateException(
                    "mall.payment.channel=alipay 但支付宝密钥未配齐，请检查 mall.payment.alipay.{app-id,private-key,public-key}");
        }
        log.info("[支付] 已启用支付宝沙箱渠道，网关 {}", properties.getAlipay().getGatewayUrl());
    }

    @Override
    public String channelCode() {
        return CHANNEL;
    }

    @Override
    public PrepayResult prepay(String outTradeNo, BigDecimal amount, String subject, Duration timeout) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", outTradeNo);
        biz.put("total_amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        biz.put("subject", subject);
        biz.put("timeout_express", timeoutExpress(timeout));

        JsonNode response = execute("alipay.trade.precreate", biz);
        String qrCode = response.path("qr_code").asText(null);
        if (!StringUtils.hasText(qrCode)) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝预下单未返回 qr_code");
        }
        log.info("[支付·支付宝] 预下单 {} 金额 ¥{} → {}", outTradeNo, amount, qrCode);
        return new PrepayResult(qrCode);
    }

    @Override
    public ChannelTradeState query(String outTradeNo) {
        try {
            JsonNode response = execute("alipay.trade.query", Map.of("out_trade_no", outTradeNo));
            return new ChannelTradeState(
                    outTradeNo,
                    response.path("trade_no").asText(null),
                    parseAmount(response.path("total_amount").asText(null)),
                    mapStatus(response.path("trade_status").asText(null)));
        } catch (AlipayApiException e) {
            if (SUB_CODE_TRADE_NOT_EXIST.equals(e.subCode)) {
                return new ChannelTradeState(outTradeNo, null, null, ChannelTradeStatus.NOT_FOUND);
            }
            throw e;
        }
    }

    @Override
    public void close(String outTradeNo) {
        try {
            execute("alipay.trade.close", Map.of("out_trade_no", outTradeNo));
            log.info("[支付·支付宝] 关单成功 {}", outTradeNo);
        } catch (Exception e) {
            // best-effort：本地订单已经关了，渠道侧关不掉只会多一笔待退款，不该阻断主流程
            log.warn("[支付·支付宝] 关单失败 {}：{}", outTradeNo, e.getMessage());
        }
    }

    @Override
    public RefundResult refund(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", outTradeNo);
        biz.put("refund_amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        // out_request_no 是支付宝侧的幂等键：同一笔用同一个编号重复退款不会被退两次
        biz.put("out_request_no", outRequestNo);
        if (StringUtils.hasText(reason)) {
            biz.put("refund_reason", reason);
        }
        try {
            JsonNode response = execute("alipay.trade.refund", biz);
            String fundChange = response.path("fund_change").asText("");
            log.info("[支付·支付宝] 退款 {} 金额 ¥{} fund_change={}", outTradeNo, amount, fundChange);
            return new RefundResult(true, response.path("trade_no").asText(null), response.toString(), null);
        } catch (Exception e) {
            log.error("[支付·支付宝] 退款失败 {}：{}", outTradeNo, e.getMessage());
            return new RefundResult(false, null, null, e.getMessage());
        }
    }

    /**
     * 验签 + 校验通知归属。
     *
     * <p><b>只验签是不够的</b>：签名只证明报文没被篡改，还要确认这笔通知是发给自己的
     * （{@code app_id} 必须一致；配了 {@code seller-id} 再校验收款方）。</p>
     */
    @Override
    public boolean verifyNotify(Map<String, String> params) {
        PaymentProperties.Alipay cfg = properties.getAlipay();
        String charset = params.getOrDefault("charset", cfg.getCharset());
        if (!AlipaySigner.verify(params, cfg.getPublicKey(), charset)) {
            return false;
        }
        String appId = params.get("app_id");
        if (!cfg.getAppId().equals(appId)) {
            log.warn("[支付·支付宝] 通知 app_id 不匹配：收到 {}，期望 {}", appId, cfg.getAppId());
            return false;
        }
        String sellerId = params.get("seller_id");
        if (StringUtils.hasText(cfg.getSellerId())
                && StringUtils.hasText(sellerId)
                && !cfg.getSellerId().equals(sellerId)) {
            log.warn("[支付·支付宝] 通知 seller_id 不匹配：收到 {}，期望 {}", sellerId, cfg.getSellerId());
            return false;
        }
        return true;
    }

    /* ------------------------------ 网关调用 ------------------------------ */

    /** 组装公共参数 → 签名 → POST 表单 → 取响应节点并判 code。 */
    private JsonNode callGateway(String method, Map<String, Object> bizContent) throws Exception {
        PaymentProperties.Alipay cfg = properties.getAlipay();
        Charset charset = Charset.forName(cfg.getCharset());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", cfg.getAppId());
        params.put("method", method);
        params.put("format", cfg.getFormat());
        params.put("charset", cfg.getCharset());
        params.put("sign_type", cfg.getSignType());
        params.put("timestamp", LocalDateTime.now().format(TIMESTAMP));
        params.put("version", cfg.getVersion());
        // biz_content 必须与最终提交的字节完全一致：先序列化一次，签名与发送都用它
        params.put("biz_content", objectMapper.writeValueAsString(bizContent));
        if (StringUtils.hasText(cfg.getNotifyUrl())) {
            params.put("notify_url", cfg.getNotifyUrl());
        }
        params.put("sign", AlipaySigner.sign(params, cfg.getPrivateKey(), cfg.getCharset(), cfg.getSignType()));

        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.getGatewayUrl()))
                .timeout(cfg.getReadTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded;charset=" + cfg.getCharset())
                .POST(HttpRequest.BodyPublishers.ofString(AlipaySigner.buildFormBody(params), charset))
                .build();

        HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(charset));
        if (response.statusCode() != 200) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝网关返回 HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode node = root.path(responseNode(method));
        if (node.isMissingNode()) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝响应缺少节点 " + responseNode(method));
        }
        String code = node.path("code").asText();
        if (!CODE_SUCCESS.equals(code)) {
            throw new AlipayApiException(
                    node.path("sub_code").asText(null),
                    node.path("sub_msg").asText(node.path("msg").asText(null)));
        }
        // 说明：支付宝响应的 sign 是对响应节点「原始 JSON 子串」的签名，
        // 反序列化再序列化会破坏字节一致性，因此本轮不做响应验签（见 package-info 的取舍说明）
        return node;
    }

    /** 统一走熔断器：网关变慢/不可用时尽快失败，避免占满应用线程。 */
    private JsonNode execute(String method, Map<String, Object> bizContent) {
        try {
            return resilienceGuard.execute(RESOURCE, () -> callGateway(method, bizContent));
        } catch (AlipayApiException | BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝接口调用失败：" + e.getMessage());
        }
    }

    /** {@code alipay.trade.precreate} → {@code alipay_trade_precreate_response}。 */
    private String responseNode(String method) {
        return method.replace('.', '_') + "_response";
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(properties.getAlipay().getConnectTimeout())
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    /** 把剩余秒数转成支付宝的 {@code timeout_express}（如 {@code 15m}，范围 1m ~ 15d）。 */
    private String timeoutExpress(Duration timeout) {
        long seconds = timeout == null ? 900L : timeout.getSeconds();
        long minutes = Math.max(1L, (seconds + 59) / 60);
        return Math.min(minutes, MAX_TIMEOUT_MINUTES) + "m";
    }

    private ChannelTradeStatus mapStatus(String tradeStatus) {
        if (tradeStatus == null) {
            return ChannelTradeStatus.NOT_FOUND;
        }
        return switch (tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> ChannelTradeStatus.SUCCESS;
            case "TRADE_CLOSED" -> ChannelTradeStatus.CLOSED;
                // WAIT_BUYER_PAY 等中间态统一按「已下单未付款」处理
            default -> ChannelTradeStatus.WAIT_PAY;
        };
    }

    private BigDecimal parseAmount(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 支付宝返回非成功码。 */
    private static class AlipayApiException extends RuntimeException {

        private final String subCode;

        AlipayApiException(String subCode, String message) {
            super("支付宝接口返回错误：" + subCode + " " + message);
            this.subCode = subCode;
        }
    }
}
