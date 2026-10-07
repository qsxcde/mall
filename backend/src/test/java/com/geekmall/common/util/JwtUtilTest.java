package com.geekmall.common.util;

import com.geekmall.common.constant.SecurityConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 工具单元测试。
 *
 * <p>用反射注入 secret / 有效期后手工调用 {@code init()}，
 * 避免为了测一个纯函数而启动整个 Spring 容器。</p>
 */
class JwtUtilTest {

    private static final String SECRET = "geek-mall-unit-test-secret-key-0123456789";
    private static final long EXPIRE_MINUTES = 30L;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expireMinutes", EXPIRE_MINUTES);
        jwtUtil.init();
    }

    @Nested
    @DisplayName("初始化")
    class Init {

        @Test
        @DisplayName("密钥不足 32 字节时启动即失败，避免用弱密钥签发出可被伪造的令牌")
        void shouldRejectShortSecret() {
            JwtUtil weak = new JwtUtil();
            ReflectionTestUtils.setField(weak, "secret", "too-short");
            ReflectionTestUtils.setField(weak, "expireMinutes", EXPIRE_MINUTES);

            assertThatThrownBy(weak::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mall.jwt.secret");
        }

        @Test
        @DisplayName("有效期以秒为单位对外暴露")
        void shouldExposeExpireSeconds() {
            assertThat(jwtUtil.getExpireSeconds()).isEqualTo(EXPIRE_MINUTES * 60);
        }
    }

    @Nested
    @DisplayName("买家令牌")
    class BuyerToken {

        @Test
        @DisplayName("签发后可解析出用户 ID 与账号名")
        void shouldRoundTrip() {
            String token = jwtUtil.createToken(42L, "13800000000");

            Claims claims = jwtUtil.parseToken(token);
            assertThat(claims.getSubject()).isEqualTo("42");
            assertThat(claims.get("username", String.class)).isEqualTo("13800000000");
            assertThat(jwtUtil.getUserId(token)).isEqualTo(42L);
            assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        }

        @Test
        @DisplayName("买家令牌不带商家作用域，防止被误当作商家身份")
        void shouldNotCarryMerchantScope() {
            Claims claims = jwtUtil.parseToken(jwtUtil.createToken(1L, "buyer"));
            assertThat(claims.get(SecurityConstants.SCOPE_CLAIM, String.class)).isNull();
            assertThat(claims.get(SecurityConstants.SHOP_ID_CLAIM, String.class)).isNull();
        }
    }

    @Nested
    @DisplayName("商家令牌")
    class MerchantToken {

        @Test
        @DisplayName("携带 scope=merchant 与 shopId，鉴权时无需回查数据库")
        void shouldCarryScopeAndShopId() {
            String token = jwtUtil.createToken(7L, "merchant", SecurityConstants.SCOPE_MERCHANT, 9L);

            Claims claims = jwtUtil.parseToken(token);
            assertThat(claims.getSubject()).isEqualTo("7");
            assertThat(claims.get(SecurityConstants.SCOPE_CLAIM, String.class))
                    .isEqualTo(SecurityConstants.SCOPE_MERCHANT);
            assertThat(claims.get(SecurityConstants.SHOP_ID_CLAIM)).isNotNull();
            assertThat(claims.get(SecurityConstants.SHOP_ID_CLAIM, Number.class).longValue()).isEqualTo(9L);
        }

        @Test
        @DisplayName("shopId 为空时不写入该声明，避免解析出 null 值声明")
        void shouldOmitShopIdWhenAbsent() {
            Claims claims = jwtUtil.parseToken(
                    jwtUtil.createToken(7L, "merchant", SecurityConstants.SCOPE_MERCHANT, null));
            assertThat(claims.get(SecurityConstants.SHOP_ID_CLAIM)).isNull();
        }
    }

    @Nested
    @DisplayName("令牌校验")
    class Verification {

        @Test
        @DisplayName("被篡改的令牌解析失败")
        void shouldRejectTamperedToken() {
            String token = jwtUtil.createToken(1L, "user");
            String tampered = token.substring(0, token.length() - 3) + "abc";

            assertThatThrownBy(() -> jwtUtil.parseToken(tampered))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("用其他密钥签发的令牌解析失败")
        void shouldRejectTokenSignedByOtherKey() {
            JwtUtil other = new JwtUtil();
            ReflectionTestUtils.setField(other, "secret", "another-unit-test-secret-key-0123456789xyz");
            ReflectionTestUtils.setField(other, "expireMinutes", EXPIRE_MINUTES);
            other.init();

            String foreign = other.createToken(1L, "user");

            assertThatThrownBy(() -> jwtUtil.parseToken(foreign))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("已过期的令牌解析失败")
        void shouldRejectExpiredToken() {
            JwtUtil expired = new JwtUtil();
            ReflectionTestUtils.setField(expired, "secret", SECRET);
            ReflectionTestUtils.setField(expired, "expireMinutes", -1L);
            expired.init();

            String token = expired.createToken(1L, "user");

            assertThatThrownBy(() -> expired.parseToken(token))
                    .isInstanceOf(JwtException.class);
        }
    }
}
