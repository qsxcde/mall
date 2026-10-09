package com.geekmall.modules.payment.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付配置（{@code mall.payment.*}）。
 *
 * <p><b>默认走本地渠道</b>：无需任何密钥即可启动，行为与旧 mock 一致；
 * 把 {@code mall.payment.channel} 设为 {@code alipay} 并配好密钥后切换到支付宝沙箱。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.payment")
public class PaymentProperties {

    /** 渠道：local（本地模拟）/ alipay（支付宝沙箱）。 */
    private String channel = "local";

    private Alipay alipay = new Alipay();

    private Compensation compensation = new Compensation();

    private StatusQuery statusQuery = new StatusQuery();

    @Data
    public static class Compensation {

        /** 是否启用「主动查单补偿」定时任务。 */
        private boolean enabled = true;

        /** 单轮扫描条数上限。 */
        private int batchSize = 100;

        /** 只处理创建满该秒数的待支付单（避开刚下单、渠道还没回调的正常窗口）。 */
        private long minAgeSeconds = 60;

        /** 超过该秒数仍未支付的单不再查（多半已本地关闭，查了也无意义）。 */
        private long maxAgeSeconds = 1800;
    }

    /**
     * 「查询支付状态时顺带主动查单」—— 无公网回调通道（内网穿透）时的主链路。
     *
     * <p>用户付款后，前端的 3s 轮询会驱动我们主动问支付宝要结果，
     * 因此不必等补偿任务那 15s 一轮；代价是需要在有用户在页面上等待时才发生。</p>
     */
    @Data
    public static class StatusQuery {

        /** 是否启用（关掉后 /status 只读本地库，行为回到纯轮询补偿）。 */
        private boolean enabled = true;

        /** 同一支付单的查单节流窗口（秒），防止多页面轮询打爆网关。 */
        private long throttleSeconds = 5;
    }

    @Data
    public static class Alipay {

        /** 应用 appId（沙箱应用）。 */
        private String appId;

        /** 应用私钥（PKCS#8 或 PKCS#1，可带 PEM 头尾）。 */
        private String privateKey;

        /** 支付宝公钥（用于回调验签）。 */
        private String publicKey;

        /**
         * 收款方 PID（可选）。
         *
         * <p>配了就对通知里的 {@code seller_id} 做一致性校验：验签只能证明报文完整，
         * 不能证明「这笔钱打给了你」。</p>
         */
        private String sellerId;

        /** 沙箱网关。 */
        private String gatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

        /** 异步通知地址（需公网可达，本地联调走隧道）。 */
        private String notifyUrl;

        private String signType = "RSA2";

        private String charset = "utf-8";

        private String format = "json";

        /** 接口版本，支付宝固定 1.0。 */
        private String version = "1.0";

        private Duration connectTimeout = Duration.ofSeconds(3);

        private Duration readTimeout = Duration.ofSeconds(10);

        /** 密钥是否齐备（齐备才允许切到 alipay 渠道）。 */
        public boolean ready() {
            return appId != null
                    && !appId.isBlank()
                    && privateKey != null
                    && !privateKey.isBlank()
                    && publicKey != null
                    && !publicKey.isBlank();
        }
    }
}
