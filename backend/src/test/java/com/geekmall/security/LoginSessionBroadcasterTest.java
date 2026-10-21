package com.geekmall.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话失效广播载荷的编解码契约。
 *
 * <p>发布端与监听端靠这个字符串通信，格式一旦不一致，表现是「登出后旧令牌还能用」
 * 这类安全问题，而不是编译错误，因此必须用测试钉死。</p>
 */
class LoginSessionBroadcasterTest {

    @Test
    @DisplayName("登出/改密载荷只有 userId，解析不出新令牌")
    void parseUserIdOnlyPayload() {
        String payload = "42";

        assertThat(LoginSessionBroadcaster.parseUserId(payload)).isEqualTo(42L);
        assertThat(LoginSessionBroadcaster.parseNewToken(payload)).isNull();
    }

    @Test
    @DisplayName("顶下线载荷可解析出 userId 与新令牌")
    void parsePayloadWithNewToken() {
        String payload = "42" + LoginSessionBroadcaster.SEPARATOR + "eyJhbGciOiJIUzI1NiJ9.payload.sig";

        assertThat(LoginSessionBroadcaster.parseUserId(payload)).isEqualTo(42L);
        assertThat(LoginSessionBroadcaster.parseNewToken(payload)).isEqualTo("eyJhbGciOiJIUzI1NiJ9.payload.sig");
    }

    @Test
    @DisplayName("非法或空载荷一律解析为 null，监听端应忽略")
    void parseInvalidPayload() {
        assertThat(LoginSessionBroadcaster.parseUserId(null)).isNull();
        assertThat(LoginSessionBroadcaster.parseUserId("")).isNull();
        assertThat(LoginSessionBroadcaster.parseUserId("  ")).isNull();
        assertThat(LoginSessionBroadcaster.parseUserId("not-a-number|token")).isNull();

        assertThat(LoginSessionBroadcaster.parseNewToken(null)).isNull();
        assertThat(LoginSessionBroadcaster.parseNewToken("42|")).isNull();
    }
}
