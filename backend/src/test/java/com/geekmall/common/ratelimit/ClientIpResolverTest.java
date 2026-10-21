package com.geekmall.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 客户端 IP 解析测试：核心是「不可信来源的转发头一律忽略」。
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolver(List<String> trustedProxies) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxies(trustedProxies);
        return new ClientIpResolver(properties);
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    @DisplayName("未配置可信代理：伪造 X-Forwarded-For 不生效")
    void ignoresForwardedHeaderWithoutTrustedProxy() {
        MockHttpServletRequest request = request("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(resolver(List.of()).resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("来源不是可信代理：即使带了转发头也忽略")
    void ignoresForwardedHeaderFromUntrustedSource() {
        MockHttpServletRequest request = request("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("可信代理链：从右往左跳过可信代理，取最右侧真实客户端")
    void resolvesRightmostUntrustedAddress() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.2, 10.0.0.1");

        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("来源可信但没有 XFF 时，才使用 X-Real-IP")
    void fallsBackToRealIpForTrustedSource() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.9");

        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("转发头出现非 IP 字符串时不抛异常，按不可信处理")
    void invalidForwardedValueDoesNotThrow() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("X-Forwarded-For", "unknown");

        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("unknown");
    }
}
