package com.geekmall.modules.payment.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 支付宝 RSA2 签名 / 验签（纯 JDK 实现，不依赖官方 SDK）。
 *
 * <p><b>签名串算法（以支付宝网关实际行为为准）</b>：</p>
 * <ol>
 *     <li>取请求/通知的<b>全部参数</b>，剔除 {@code sign}，剔除空值；</li>
 *     <li>按参数名<b>字典序</b>升序；</li>
 *     <li>拼成 {@code k1=v1&k2=v2...}（<b>值不做 URL 编码</b>，用原始值）；</li>
 *     <li>RSA2 = {@code SHA256withRSA}（RSA 为 {@code SHA1withRSA}）签名，结果 Base64。</li>
 * </ol>
 *
 * <p>⚠️ {@code sign_type} <b>参与</b>签名（见 {@link #buildSignContent} 里的说明与实证）。</p>
 *
 * <p><b>传输编码</b>：签名串里的值是<b>原始值</b>，但表单提交时每个值都要 URL 编码
 * （尤其 {@code biz_content} 含 {@code {}":,}、{@code timestamp} 含空格与冒号）——
 * 见 {@link #buildFormBody(Map)}。二者不可混淆，否则验签必失败。</p>
 *
 * <p><b>私钥格式</b>：优先按 PKCS#8 解析；若失败则视为 PKCS#1（{@code BEGIN RSA PRIVATE KEY}）
 * 并自动包装成 PKCS#8 —— 支付宝密钥工具导出的「非 Java 格式」私钥就是 PKCS#1。</p>
 */
public final class AlipaySigner {

    /** RSA2 对应的 JCA 算法名。 */
    private static final String ALG_RSA2 = "SHA256withRSA";
    /** 旧版 RSA（支付宝已不推荐，仅做兼容）。 */
    private static final String ALG_RSA = "SHA1withRSA";

    private static final String SIGN_TYPE_RSA2 = "RSA2";

    private AlipaySigner() {}

    /* ------------------------------ 签名 / 验签 ------------------------------ */

    /**
     * 对待签名参数做 RSA2 签名。
     *
     * @param params     参与签名的参数（可含 sign/sign_type，会在拼接时剔除）
     * @param privateKey 应用私钥（PKCS#8 或 PKCS#1，可带 PEM 头尾）
     * @param charset    字符集，默认 utf-8
     * @return Base64 签名串
     */
    public static String sign(Map<String, String> params, String privateKey, String charset) {
        return sign(params, privateKey, charset, SIGN_TYPE_RSA2);
    }

    public static String sign(Map<String, String> params, String privateKey, String charset, String signType) {
        try {
            Signature signature = Signature.getInstance(algorithm(signType));
            signature.initSign(loadPrivateKey(privateKey));
            signature.update(buildSignContent(params).getBytes(charsetOf(charset)));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝签名失败：" + e.getMessage());
        }
    }

    /**
     * 校验支付宝通知 / 响应签名。
     *
     * <p>若参数里没有 {@code sign}，或解析/验签过程出错，一律返回 {@code false}
     * （验签失败不应抛出，避免把渠道通知变成 500）。</p>
     */
    public static boolean verify(Map<String, String> params, String publicKey, String charset) {
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(algorithm(params.get("sign_type")));
            signature.initVerify(loadPublicKey(publicKey));
            signature.update(buildSignContent(params).getBytes(charsetOf(charset)));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构造待签名字符串：<b>只剔除 {@code sign}</b> 与空值 → 字典序 → {@code k=v&}。
     *
     * <p><b>{@code sign_type} 必须参与签名</b>（易错点）：网上大量文章写「除去 sign、sign_type」，
     * 但支付宝网关实际使用（并在报错里回显）的验签串是<b>包含 sign_type</b> 的。
     * 联调时网关报 {@code isv.invalid-signature} 并回显：
     * {@code ...&method=alipay.trade.precreate&sign_type=RSA2&timestamp=...&version=1.0} ——
     * 与我们的签名串只差 {@code sign_type=RSA2} 这一段，剔除它必然验签失败。
     * 以服务器实际行为为准。</p>
     *
     * <p>公开以便单测直接断言拼接结果。</p>
     */
    public static String buildSignContent(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isEmpty() || "sign".equals(key)) {
                continue;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            sorted.put(key, value);
        }
        return sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    /**
     * 构造 form 表单请求体：每个值按 charset 做 URL 编码，键值以 {@code &} 连接。
     *
     * <p>用 {@code URLEncoder} 会顺手把空格编成 {@code +}，这对
     * {@code application/x-www-form-urlencoded} 是正确的（服务端会解回空格），
     * 支付宝网关同样按表单解析。</p>
     */
    public static String buildFormBody(Map<String, String> params) {
        Charset cs = StandardCharsets.UTF_8;
        return params.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> urlEncode(e.getKey(), cs) + "=" + urlEncode(e.getValue(), cs))
                .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value, Charset charset) {
        return java.net.URLEncoder.encode(value, charset);
    }

    /* ------------------------------ 密钥加载 ------------------------------ */

    private static PrivateKey loadPrivateKey(String key) throws Exception {
        byte[] der = decodeKey(key);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        try {
            return factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (InvalidKeySpecException notPkcs8) {
            // 支付宝密钥工具导出的「非 Java 格式」是 PKCS#1，纯 JDK 只能解析 PKCS#8，这里做个包装
            return factory.generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1ToPkcs8(der)));
        }
    }

    private static PublicKey loadPublicKey(String key) throws Exception {
        byte[] der = decodeKey(key);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** 去掉 PEM 头尾与所有空白，Base64 解码。 */
    private static byte[] decodeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝密钥未配置");
        }
        String normalized = key.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.BIZ_ERROR, "支付宝密钥不是合法 Base64");
        }
    }

    /**
     * 把 PKCS#1（RSAPrivateKey）DER 包装成 PKCS#8（PrivateKeyInfo）DER。
     *
     * <pre>
     * PrivateKeyInfo ::= SEQUENCE {
     *     version             INTEGER (0),
     *     privateKeyAlgorithm SEQUENCE { OID rsaEncryption, NULL },
     *     privateKey          OCTET STRING   -- 内含 PKCS#1
     * }
     * </pre>
     */
    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        // SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
        byte[] algorithmId = {
            0x30,
            0x0d,
            0x06,
            0x09,
            0x2a,
            (byte) 0x86,
            0x48,
            (byte) 0x86,
            (byte) 0xf7,
            0x0d,
            0x01,
            0x01,
            0x01,
            0x05,
            0x00
        };
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(version);
        body.writeBytes(algorithmId);
        body.writeBytes(derEncode(0x04, pkcs1));
        return derEncode(0x30, body.toByteArray());
    }

    /** DER: 单字节 tag + 长度 + 内容。 */
    private static byte[] derEncode(int tag, byte[] content) {
        byte[] length = derLength(content.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.writeBytes(length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    /** DER 长度编码（最短形式）。 */
    private static byte[] derLength(int len) {
        if (len < 0x80) {
            return new byte[] {(byte) len};
        }
        if (len <= 0xFF) {
            return new byte[] {(byte) 0x81, (byte) len};
        }
        if (len <= 0xFFFF) {
            return new byte[] {(byte) 0x82, (byte) (len >> 8), (byte) len};
        }
        return new byte[] {(byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
    }

    /* ------------------------------ 工具 ------------------------------ */

    private static String algorithm(String signType) {
        // 缺省按 RSA2（支付宝现已强制 RSA2）
        return signType != null && !SIGN_TYPE_RSA2.equalsIgnoreCase(signType) ? ALG_RSA : ALG_RSA2;
    }

    private static Charset charsetOf(String charset) {
        if (charset == null || charset.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charset);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
