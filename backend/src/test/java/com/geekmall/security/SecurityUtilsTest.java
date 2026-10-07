package com.geekmall.security;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录上下文读取工具单元测试。
 *
 * <p>这两个工具是「数据隔离」的入口：买家侧决定看谁的订单，商家侧决定能查哪家店的数据。
 * 未登录时必须抛 401，而 {@code getUserIdOrNull} 必须安静返回 null（供领券中心等匿名可访问接口使用）。</p>
 */
class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Nested
    @DisplayName("买家侧")
    class Buyer {

        @Test
        @DisplayName("未登录时读取用户 ID 抛 401")
        void shouldThrowWhenAnonymous() {
            assertThatThrownBy(SecurityUtils::getUserId)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ResultCode.UNAUTHORIZED.getCode());
        }

        @Test
        @DisplayName("未登录时 getUserIdOrNull 返回 null，不抛异常")
        void shouldReturnNullWhenAnonymous() {
            assertThat(SecurityUtils.getUserIdOrNull()).isNull();
        }

        @Test
        @DisplayName("登录后可读取用户 ID / 账号 / 上下文对象")
        void shouldReadLoginUser() {
            LoginUser loginUser = new LoginUser(99L, "13800000000", "极客小张");
            authenticate(loginUser);

            assertThat(SecurityUtils.getUserId()).isEqualTo(99L);
            assertThat(SecurityUtils.getUserIdOrNull()).isEqualTo(99L);
            assertThat(SecurityUtils.getUsername()).isEqualTo("13800000000");
            assertThat(SecurityUtils.getLoginUser().getNickname()).isEqualTo("极客小张");
        }

        @Test
        @DisplayName("商家令牌出现在买家侧上下文时按未登录处理，避免跨端越权")
        void shouldRejectMerchantPrincipalOnBuyerSide() {
            authenticate(new MerchantLoginUser(1L, "merchant", 1L, "极客数码旗舰店"));

            assertThatThrownBy(SecurityUtils::getUserId).isInstanceOf(BizException.class);
            assertThat(SecurityUtils.getUserIdOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("商家侧")
    class Merchant {

        @Test
        @DisplayName("未登录时读取店铺 ID 抛 401")
        void shouldThrowWhenAnonymous() {
            assertThatThrownBy(MerchantSecurityUtils::getShopId)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ResultCode.UNAUTHORIZED.getCode());
        }

        @Test
        @DisplayName("登录后可读取店铺 ID 与商家账号 ID")
        void shouldReadShopId() {
            authenticate(new MerchantLoginUser(7L, "merchant", 3L, "极客数码旗舰店"));

            assertThat(MerchantSecurityUtils.getShopId()).isEqualTo(3L);
            assertThat(MerchantSecurityUtils.getMerchantUserId()).isEqualTo(7L);
            assertThat(MerchantSecurityUtils.getLoginUser().getShopName()).isEqualTo("极客数码旗舰店");
        }

        @Test
        @DisplayName("买家令牌出现在商家侧上下文时按未登录处理，避免越权访问商家接口")
        void shouldRejectBuyerPrincipalOnMerchantSide() {
            authenticate(new LoginUser(99L, "13800000000", "极客小张"));

            assertThatThrownBy(MerchantSecurityUtils::getShopId).isInstanceOf(BizException.class);
        }
    }
}
