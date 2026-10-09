package com.geekmall.modules.payment.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付配置自检。
 *
 * <p>把「密钥配好了、但渠道开关没切」这类<b>静默误配</b>在启动时就喊出来。</p>
 *
 * <p>之所以需要它：{@code mall.payment.channel} 默认 {@code local}，
 * 配了密钥却忘记改这个开关时，程序会安安静静地继续走本地模拟渠道 ——
 * 表现为「我明明填了密钥，怎么还是本地支付」，排查成本很高
 * （本次联调就踩了这个坑）。与其让人去猜，不如启动时直接打印一行警告。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfigValidator {

    private static final String CHANNEL_ALIPAY = "alipay";

    private final PaymentProperties properties;

    @PostConstruct
    void validate() {
        PaymentProperties.Alipay alipay = properties.getAlipay();
        boolean switchedToAlipay = CHANNEL_ALIPAY.equalsIgnoreCase(properties.getChannel());
        if (!switchedToAlipay && alipay.ready()) {
            log.warn(
                    "[支付] 检测到已配置支付宝密钥（appId/私钥/公钥），但 mall.payment.channel={} —— 仍在走本地模拟渠道。"
                            + "若要走支付宝沙箱，请把渠道开关设为 alipay（infra/.env 的 MALL_PAYMENT_CHANNEL=alipay）。",
                    properties.getChannel());
        }
    }
}
