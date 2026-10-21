package com.geekmall.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 生产配置自检单元测试。
 *
 * <p>这段逻辑的价值全在「它真的会拦住人」：如果漏判，生产会带着公开默认密钥
 * 或演示账号启动，而且不报任何错。因此每个开关都单独验证一次拦截，
 * 并验证非 prod profile 下不误伤本地开发。</p>
 */
class ProdConfigGuardTest {

    private static final String LEAKED_SECRET = "geek-mall-jwt-secret-key-please-change-in-production-2026";
    private static final String GOOD_SECRET = "a-very-long-random-production-secret-0123456789abcdef";

    /** 构造一个「全部合规」的 prod 环境，再由各用例破坏其中一项。 */
    private ProdConfigGuard guard(
            boolean prod,
            String secret,
            boolean demoUser,
            boolean demoMerchant,
            boolean mock,
            boolean exposeCode,
            boolean rateLimit) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(prod ? new String[] {"prod"} : new String[] {"dev"});

        ProdConfigGuard guard = new ProdConfigGuard(env);
        ReflectionTestUtils.setField(guard, "jwtSecret", secret);
        ReflectionTestUtils.setField(guard, "initDemoUser", demoUser);
        ReflectionTestUtils.setField(guard, "initDemoMerchant", demoMerchant);
        ReflectionTestUtils.setField(guard, "mockEnabled", mock);
        ReflectionTestUtils.setField(guard, "smsExposeCode", exposeCode);
        ReflectionTestUtils.setField(guard, "rateLimitEnabled", rateLimit);
        // prod 默认关闭 Swagger，并配置具体跨域来源
        ReflectionTestUtils.setField(guard, "apiDocsEnabled", false);
        ReflectionTestUtils.setField(guard, "corsAllowedOrigins", "https://mall.example.com");
        // 默认视为「生产已切到真实支付渠道」；需要验证本地渠道拦截时再单独覆盖。
        ReflectionTestUtils.setField(guard, "paymentChannel", "alipay");
        ReflectionTestUtils.setField(guard, "allowLocalPaymentInProd", false);
        return guard;
    }

    private ProdConfigGuard compliant() {
        return guard(true, GOOD_SECRET, false, false, false, false, true);
    }

    @Nested
    @DisplayName("合规配置")
    class Compliant {

        @Test
        @DisplayName("prod 下全部开关正确时正常启动")
        void compliantProdStarts() {
            assertThatCode(() -> compliant().validate()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非 prod profile 完全不校验：本地开发可以随意用回显验证码与 mock")
        void nonProdSkipsCheck() {
            ProdConfigGuard dev = guard(false, LEAKED_SECRET, true, true, true, true, false);
            assertThatCode(dev::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("JWT 密钥")
    class JwtSecret {

        @Test
        @DisplayName("使用源码里的公开默认密钥 → 拒绝启动")
        void leakedDefaultSecretIsRejected() {
            ProdConfigGuard guard = guard(true, LEAKED_SECRET, false, false, false, false, true);

            assertThatThrownBy(guard::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("公开默认值");
        }

        @Test
        @DisplayName("密钥为空 → 拒绝启动（而不是用空串签名）")
        void blankSecretIsRejected() {
            ProdConfigGuard guard = guard(true, "", false, false, false, false, true);

            assertThatThrownBy(guard::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mall.jwt.secret 未设置");
        }
    }

    @Nested
    @DisplayName("支付渠道")
    class PaymentChannel {

        @Test
        @DisplayName("prod 使用本地模拟渠道 → 拒绝启动（/mock-pay 可零元购）")
        void localChannelIsRejected() {
            ProdConfigGuard guard = compliant();
            ReflectionTestUtils.setField(guard, "paymentChannel", "local");

            assertThatThrownBy(guard::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("本地模拟支付渠道");
        }

        @Test
        @DisplayName("渠道为空同样按本地渠道处理，避免空值绕过校验")
        void blankChannelIsRejected() {
            ProdConfigGuard guard = compliant();
            ReflectionTestUtils.setField(guard, "paymentChannel", " ");

            assertThatThrownBy(guard::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("本地模拟支付渠道");
        }

        @Test
        @DisplayName("本地多实例验证显式放行时必须明确设置开关")
        void explicitOverrideAllowsLocalChannel() {
            ProdConfigGuard guard = compliant();
            ReflectionTestUtils.setField(guard, "paymentChannel", "local");
            ReflectionTestUtils.setField(guard, "allowLocalPaymentInProd", true);

            assertThatCode(guard::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("接口文档与跨域")
    class ApiDocsAndCors {

        @Test
        @DisplayName("prod 开启 Swagger → 拒绝启动")
        void apiDocsEnabledIsRejected() {
            ProdConfigGuard guard = compliant();
            ReflectionTestUtils.setField(guard, "apiDocsEnabled", true);

            assertThatThrownBy(guard::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Swagger");
        }

        @Test
        @DisplayName("prod 跨域配置含通配符 → 拒绝启动")
        void wildcardCorsIsRejected() {
            ProdConfigGuard guard = compliant();
            ReflectionTestUtils.setField(guard, "corsAllowedOrigins", "*");

            assertThatThrownBy(guard::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("通配符");
        }
    }

    @Nested
    @DisplayName("dev 便利开关泄漏到生产")
    class DevSwitches {

        @Test
        @DisplayName("演示买家账号 → 拒绝启动")
        void demoUserIsRejected() {
            ProdConfigGuard guard = guard(true, GOOD_SECRET, true, false, false, false, true);
            assertThatThrownBy(guard::validate).hasMessageContaining("演示买家账号");
        }

        @Test
        @DisplayName("演示商家账号 → 拒绝启动")
        void demoMerchantIsRejected() {
            ProdConfigGuard guard = guard(true, GOOD_SECRET, false, true, false, false, true);
            assertThatThrownBy(guard::validate).hasMessageContaining("演示商家账号");
        }

        @Test
        @DisplayName("mock 接口开启 → 拒绝启动")
        void mockIsRejected() {
            ProdConfigGuard guard = guard(true, GOOD_SECRET, false, false, true, false, true);
            assertThatThrownBy(guard::validate).hasMessageContaining("模拟发货");
        }

        @Test
        @DisplayName("验证码回显 → 拒绝启动")
        void smsExposeCodeIsRejected() {
            ProdConfigGuard guard = guard(true, GOOD_SECRET, false, false, false, true, true);
            assertThatThrownBy(guard::validate).hasMessageContaining("验证码");
        }

        @Test
        @DisplayName("关闭限流 → 拒绝启动")
        void rateLimitOffIsRejected() {
            ProdConfigGuard guard = guard(true, GOOD_SECRET, false, false, false, false, false);
            assertThatThrownBy(guard::validate).hasMessageContaining("限流");
        }

        @Test
        @DisplayName("多项同时违规时一次性列出全部问题（避免改一项重启一次的循环）")
        void allViolationsAreReportedTogether() {
            ProdConfigGuard guard = guard(true, LEAKED_SECRET, true, true, true, true, false);

            assertThatThrownBy(guard::validate)
                    .hasMessageContaining("公开默认值")
                    .hasMessageContaining("演示买家账号")
                    .hasMessageContaining("演示商家账号")
                    .hasMessageContaining("模拟发货")
                    .hasMessageContaining("验证码")
                    .hasMessageContaining("限流");
        }
    }
}
