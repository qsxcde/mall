package com.geekmall.modules.content.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalFileAccessSignerTest {

    private final LocalFileAccessSigner signer = new LocalFileAccessSigner("test-secret", "http://localhost:8080");

    private static Map<String, String> queryOf(String url) {
        String rawQuery = URI.create(url).getRawQuery();
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("签名 URL 可被校验通过")
    void signedUrlVerifies() {
        String path = "aftersale/2026/10/21/evidence.png";
        Map<String, String> query = queryOf(signer.signedUrl(path));

        assertThat(signer.signedUrl(path)).startsWith("http://localhost:8080/api/v1/files/local?");
        assertThat(signer.verify(path, Long.parseLong(query.get("expires")), query.get("sig")))
                .isTrue();
    }

    @Test
    @DisplayName("路径被篡改或签名过期后校验失败")
    void tamperedOrExpiredSignatureFails() {
        String path = "aftersale/2026/10/21/evidence.png";
        Map<String, String> query = queryOf(signer.signedUrl(path));
        long expires = Long.parseLong(query.get("expires"));
        String signature = query.get("sig");

        assertThat(signer.verify("aftersale/2026/10/21/other.png", expires, signature))
                .isFalse();
        assertThat(signer.verify(path, 1L, signature)).isFalse();
        assertThat(signer.verify(path, expires, signature + "x")).isFalse();
    }
}
