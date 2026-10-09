package com.geekmall.modules.payment.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AlipaySigner} 单元测试：用自生成 RSA 密钥对做签验往返，不依赖网络与真实密钥。
 */
class AlipaySignerTest {

    private static String privateKeyPkcs8;
    private static String privateKeyPkcs1;
    private static String publicKey;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        byte[] pkcs8 = pair.getPrivate().getEncoded(); // JDK 一律输出 PKCS#8
        privateKeyPkcs8 = Base64.getEncoder().encodeToString(pkcs8);
        // 从 PKCS#8 里剥出内层 RSAPrivateKey(PKCS#1)，用于验证「非 Java 格式私钥」兼容
        privateKeyPkcs1 = Base64.getEncoder().encodeToString(extractPkcs1FromPkcs8(pkcs8));
        publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    @Test
    @DisplayName("签名 → 验签 往返成功")
    void signAndVerifyRoundTrip() {
        Map<String, String> params = notifyParams();

        String sign = AlipaySigner.sign(params, privateKeyPkcs8, "utf-8");
        params.put("sign", sign);

        assertThat(AlipaySigner.verify(params, publicKey, "utf-8")).isTrue();
    }

    @Test
    @DisplayName("PKCS#1 私钥（支付宝非 Java 格式）也能签名，且能被同一公钥验签")
    void pkcs1PrivateKeyIsSupported() {
        Map<String, String> params = notifyParams();

        String sign = AlipaySigner.sign(params, privateKeyPkcs1, "utf-8");
        params.put("sign", sign);

        assertThat(AlipaySigner.verify(params, publicKey, "utf-8")).isTrue();
    }

    @Test
    @DisplayName("带 PEM 头尾与换行的私钥同样可用")
    void pemArmoredKeyIsSupported() {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + chunk(privateKeyPkcs8) + "\n-----END PRIVATE KEY-----";

        Map<String, String> params = notifyParams();
        params.put("sign", AlipaySigner.sign(params, pem, "utf-8"));

        assertThat(AlipaySigner.verify(params, publicKey, "utf-8")).isTrue();
    }

    @Test
    @DisplayName("篡改任一参数 → 验签失败")
    void tamperedParamFailsVerification() {
        Map<String, String> params = notifyParams();
        params.put("sign", AlipaySigner.sign(params, privateKeyPkcs8, "utf-8"));
        assertThat(AlipaySigner.verify(params, publicKey, "utf-8")).isTrue();

        params.put("total_amount", "0.01");

        assertThat(AlipaySigner.verify(params, publicKey, "utf-8")).isFalse();
    }

    @Test
    @DisplayName("无 sign / sign 非法 → 验签失败且不抛异常")
    void missingOrMalformedSignFailsQuietly() {
        assertThat(AlipaySigner.verify(notifyParams(), publicKey, "utf-8")).isFalse();

        Map<String, String> params = notifyParams();
        params.put("sign", "not-a-base64-signature");
        assertThat(AlipaySigner.verify(params, publicKey, "utf-8")).isFalse();
    }

    @Test
    @DisplayName("待签名串：剔除 sign/sign_type/空值，按字典序拼接")
    void signContentRules() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("total_amount", "159.00");
        params.put("out_trade_no", "PAY20261009120000001");
        params.put("sign_type", "RSA2"); // 应剔除
        params.put("sign", "xxx"); // 应剔除
        params.put("subject", ""); // 空值应剔除
        params.put("app_id", "2021000000000000");

        String content = AlipaySigner.buildSignContent(params);

        assertThat(content)
                .isEqualTo("app_id=2021000000000000" + "&out_trade_no=PAY20261009120000001" + "&total_amount=159.00");
    }

    @Test
    @DisplayName("签名串里 sign_type 不参与，因此其取值不影响签名结果")
    void signTypeDoesNotAffectSignature() {
        Map<String, String> withType = notifyParams();
        withType.put("sign_type", "RSA"); // 与另一份的 RSA2 不同，但都应被剔除
        Map<String, String> withoutType = notifyParams();

        assertThat(AlipaySigner.sign(withType, privateKeyPkcs8, "utf-8"))
                .isEqualTo(AlipaySigner.sign(withoutType, privateKeyPkcs8, "utf-8"));
    }

    @Test
    @DisplayName("表单体：特殊字符按 URL 编码，验签串则保持原值")
    void formBodyEncodesButSignContentDoesNot() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("biz_content", "{\"out_trade_no\":\"PAY1\",\"total_amount\":\"159.00\"}");
        params.put("timestamp", "2026-10-09 12:00:00");
        params.put("charset", "utf-8");

        String body = AlipaySigner.buildFormBody(params);

        // 空格编成 +，引号/花括号/冒号被转义；& = 作分隔符不转义
        assertThat(body).contains("timestamp=2026-10-09+12%3A00%3A00");
        assertThat(body).contains("%7B%22out_trade_no%22%3A%22PAY1%22");
        // 待签名串里必须保持原值，否则与网关重算的结果不一致
        assertThat(AlipaySigner.buildSignContent(params)).contains("timestamp=2026-10-09 12:00:00");
    }

    /* ------------------------------ 夹具 ------------------------------ */

    /** 一份贴近真实异步通知的参数集（字段名与支付宝一致）。 */
    private static Map<String, String> notifyParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gmt_create", "2026-10-09 12:00:00");
        params.put("charset", "utf-8");
        params.put("seller_email", "sandbox@alipay.com");
        params.put("subject", "测试商品");
        params.put("sign_type", "RSA2");
        params.put("trade_no", "2026100922001400000000000001");
        params.put("out_trade_no", "PAY20261009120000001");
        params.put("app_id", "2021000000000000");
        params.put("total_amount", "159.00");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("seller_id", "2088000000000000");
        return params;
    }

    private static String chunk(String base64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从 PKCS#8(PrivateKeyInfo) 里剥出内层 PKCS#1(RSAPrivateKey) DER，仅用于测试夹具。
     *
     * <pre>SEQUENCE { INTEGER(version), SEQUENCE(algId), OCTET STRING(pkcs1) }</pre>
     */
    private static byte[] extractPkcs1FromPkcs8(byte[] pkcs8) {
        int[] pos = {0};
        readTag(pkcs8, pos, 0x30); // 外层 SEQUENCE
        readLength(pkcs8, pos);
        readTag(pkcs8, pos, 0x02); // version INTEGER
        readLength(pkcs8, pos);
        pos[0] += 1; // version 恒为 0，占 1 字节
        readTag(pkcs8, pos, 0x30); // algorithm SEQUENCE
        int algorithmLength = readLength(pkcs8, pos);
        pos[0] += algorithmLength;
        readTag(pkcs8, pos, 0x04); // OCTET STRING
        int len = readLength(pkcs8, pos);
        byte[] pkcs1 = new byte[len];
        System.arraycopy(pkcs8, pos[0], pkcs1, 0, len);
        return pkcs1;
    }

    private static void readTag(byte[] der, int[] pos, int expected) {
        assertThat(der[pos[0]] & 0xFF).as("DER tag @%d", pos[0]).isEqualTo(expected);
        pos[0]++;
    }

    private static int readLength(byte[] der, int[] pos) {
        int first = der[pos[0]++] & 0xFF;
        if (first < 0x80) {
            return first;
        }
        int numBytes = first & 0x7F;
        int len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (der[pos[0]++] & 0xFF);
        }
        return len;
    }
}
