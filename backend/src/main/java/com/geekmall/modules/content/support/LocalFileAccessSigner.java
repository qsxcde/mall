package com.geekmall.modules.content.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘文件的签名访问地址。
 *
 * <p>本地模式没有 MinIO 的预签名能力；售后凭证等敏感文件不能直接暴露在
 * {@code /uploads/**} 下。这里用 HMAC-SHA256 生成带过期时间的签名 URL，
 * 由 {@code LocalFileAccessController} 校验后流式返回。</p>
 */
@Slf4j
@Component
public class LocalFileAccessSigner {

    /** 敏感文件签名访问端点的路径（也是「本地签名 URL」形态的识别特征）。 */
    public static final String ACCESS_PATH = "/api/v1/files/local";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 签名有效期：分钟级短时效。
     *
     * <p>签名 URL 相当于「不记名凭证」，泄露后在有效期内任何人都能下载；
     * 因此不再使用早期的 7 天长效，改为短时效 + 读取时按次重新签发
     * （见 {@code FileService.refreshUrl}）。</p>
     */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final String secret;
    private final String baseUrl;

    public LocalFileAccessSigner(
            @Value("${mall.storage.local-signing-secret:local-dev-file-signing-secret-change-me}") String secret,
            @Value("${mall.storage.local-public-base-url:http://localhost:8080}") String baseUrl) {
        this.secret = secret;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 生成敏感文件的签名访问地址（默认 15 分钟有效，读取时按次重新签发）。 */
    public String signedUrl(String relativePath) {
        long expires = Instant.now().plus(DEFAULT_TTL).getEpochSecond();
        String signature = sign(relativePath, expires);
        return baseUrl + ACCESS_PATH + "?path="
                + URLEncoder.encode(relativePath, StandardCharsets.UTF_8)
                + "&expires=" + expires + "&sig=" + signature;
    }

    /** 校验签名与过期时间。 */
    public boolean verify(String relativePath, long expires, String signature) {
        if (relativePath == null || signature == null || expires < Instant.now().getEpochSecond()) {
            return false;
        }
        String expected = sign(relativePath, expires);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String relativePath, long expires) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal((relativePath + ":" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("生成本地文件签名失败", e);
        }
    }
}
