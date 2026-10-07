package com.geekmall.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流策略表单元测试。
 *
 * <p>策略表是「全站限流规则的唯一事实来源」，因此这里验证的是表本身的自洽性：
 * 规则不重复、字段合法、顺序正确（具体规则必须先于笼统规则），
 * 以及代表性端点的解析结果符合预期。</p>
 */
class RateLimitPoliciesTest {

    private final RateLimitPolicies policies = new RateLimitPolicies();

    @Nested
    @DisplayName("表自身约束")
    class SelfConsistency {

        @Test
        @DisplayName("规则名唯一（规则名是 Redis key 的一部分，重名会让不同接口互相挤占额度）")
        void ruleNamesAreUnique() {
            assertThat(RateLimitPolicies.rules())
                    .extracting(RateLimitPolicy::name)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("「方法 + 路径模式」不重复，防止同一请求被两条规则重复计量")
        void methodPatternPairsAreUnique() {
            Map<String, String> owner = new HashMap<>();
            for (RateLimitPolicy policy : RateLimitPolicies.rules()) {
                for (String pattern : policy.patterns()) {
                    if (policy.anyMethod()) {
                        String previous = owner.put("ALL|" + pattern, policy.name());
                        assertThat(previous).as("模式 %s 被 %s 与 %s 重复配置", pattern, previous, policy.name()).isNull();
                        continue;
                    }
                    for (var method : policy.methods()) {
                        String key = method.name() + "|" + pattern;
                        String previous = owner.put(key, policy.name());
                        assertThat(previous)
                                .as("%s %s 被 %s 与 %s 重复配置", method, pattern, previous, policy.name())
                                .isNull();
                    }
                }
            }
        }

        @Test
        @DisplayName("所有规则的阈值与窗口都为正数（构造器已校验，这里固化契约）")
        void thresholdsArePositive() {
            assertThat(RateLimitPolicies.rules()).allSatisfy(policy -> {
                assertThat(policy.limit()).isPositive();
                assertThat(policy.windowSeconds()).isPositive();
                assertThat(policy.maxConcurrent()).isGreaterThanOrEqualTo(0);
                assertThat(policy.patterns()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("规则数量与表规模一致，且不存在与兜底规则同名的规则")
        void noRuleShadowsDefault() {
            assertThat(RateLimitPolicies.rules())
                    .extracting(RateLimitPolicy::name)
                    .doesNotContain(RateLimitPolicies.DEFAULT.name());
            assertThat(RateLimitPolicies.rules()).hasSizeGreaterThan(30);
        }

        @Test
        @DisplayName("兜底规则对任意路径都生效，保证不存在「完全无限流」的端点")
        void defaultCoversEverything() {
            assertThat(RateLimitPolicies.DEFAULT.patterns()).containsExactly("/**");
            assertThat(policies.resolve("GET", "/api/v1/whatever/future"))
                    .isSameAs(RateLimitPolicies.DEFAULT);
        }
    }

    @Nested
    @DisplayName("解析结果（顺序敏感）")
    class Resolution {

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
                // --- 认证：每个接口单独分类，不能被 /auth/** 之类的泛规则吞掉 ---
                "POST, /api/v1/auth/login, auth-login",
                "POST, /api/v1/auth/login/sms, auth-sms-login",
                "POST, /api/v1/auth/sms-code, auth-sms-code",
                "POST, /api/v1/auth/register, auth-register",
                "POST, /api/v1/auth/password/reset, auth-reset",
                "POST, /api/v1/auth/logout, auth-logout",
                // --- 商家登录必须早于 /merchant/** 命中 ---
                "POST, /api/v1/merchant/auth/login, merchant-login",
                "POST, /api/v1/merchant/auth/logout, merchant-logout",
                "GET, /api/v1/merchant/auth/shop, merchant-read",
                // --- 上传 ---
                "POST, /api/v1/files, file-upload",
                // --- 支付 ---
                "POST, /api/v1/pay/callback, pay-callback",
                "POST, /api/v1/pay/PAY20261001/mock-pay, pay-mock",
                "POST, /api/v1/pay/create, pay-create",
                "GET, /api/v1/pay/PAY20261001/status, pay-status",
                // --- 秒杀：抢购先于列表命中 ---
                "POST, /api/v1/seckill/12/order, seckill-grab",
                "GET, /api/v1/seckill/sessions, seckill-read",
                "GET, /api/v1/seckill/items, seckill-read",
                // --- 交易 ---
                "POST, /api/v1/trade/orders, trade-submit",
                "POST, /api/v1/trade/orders/GM1/cancel, trade-action",
                "POST, /api/v1/trade/orders/GM1/confirm, trade-action",
                "POST, /api/v1/trade/pre-order, trade-preorder",
                "POST, /api/v1/trade/mock/ship/GM1, trade-mock",
                // --- 营销 ---
                "POST, /api/v1/coupons/claim/3, coupon-claim",
                "GET, /api/v1/coupons/templates, coupon-read",
                "POST, /api/v1/points/exchange/2, points-exchange",
                "GET, /api/v1/points/goods, points-read",
                // --- 评价 / 售后 ---
                "POST, /api/v1/reviews, review-write",
                "GET, /api/v1/user/reviews, review-mine",
                "DELETE, /api/v1/user/reviews/8, review-mine",
                "GET, /api/v1/products/5/reviews, product-read",
                "GET, /api/v1/aftersales, aftersale-read",
                "GET, /api/v1/aftersales/3, aftersale-read",
                "POST, /api/v1/aftersales, aftersale-write",
                "POST, /api/v1/aftersales/3/cancel, aftersale-write",
                // --- 订单 / 购物车 / 用户中心 ---
                "GET, /api/v1/orders, order-read",
                "GET, /api/v1/orders/status-counts, order-read",
                "GET, /api/v1/orders/GM1/logistics, order-read",
                "GET, /api/v1/cart/items, cart-read",
                "GET, /api/v1/cart/summary, cart-read",
                "POST, /api/v1/cart/items, cart-write",
                "PUT, /api/v1/cart/items/1/qty, cart-write",
                "DELETE, /api/v1/cart/checked, cart-write",
                "GET, /api/v1/user/profile, user-read",
                "PUT, /api/v1/user/profile, user-write",
                "POST, /api/v1/user/sign-in, user-write",
                "GET, /api/v1/member/info, member-read",
                "GET, /api/v1/messages, message-read",
                "POST, /api/v1/messages/read-all, message-write",
                // --- 商家后台：提现先于通用写规则 ---
                "POST, /api/v1/merchant/fund/withdraw, merchant-withdraw",
                "POST, /api/v1/merchant/product/save, merchant-write",
                "POST, /api/v1/merchant/order/ship, merchant-write",
                "GET, /api/v1/merchant/overview, merchant-read",
                "GET, /api/v1/merchant/fund/summary, merchant-read",
                // --- 公开读 ---
                "GET, /api/v1/home/floors, home-read",
                "GET, /api/v1/categories/tree, category-read",
                "GET, /api/v1/products, product-read",
                "GET, /api/v1/products/9, product-read",
                "GET, /api/v1/cms/about, content-read"
        })
        void shouldResolveExpectedPolicy(String method, String path, String expectedPolicy) {
            assertThat(policies.resolve(method, path).name()).isEqualTo(expectedPolicy);
        }

        @Test
        @DisplayName("HEAD 与 GET 归入同一规则（Spring 会把 HEAD 映射到 GET 处理器）")
        void headFallsIntoReadRule() {
            assertThat(policies.resolve("HEAD", "/api/v1/products").name()).isEqualTo("product-read");
        }

        @Test
        @DisplayName("同一路径的读写规则互不干扰：只有方法集合重叠才算重复")
        void readAndWriteRulesCoexist() {
            assertThat(policies.resolve("GET", "/api/v1/cart/items").name()).isEqualTo("cart-read");
            assertThat(policies.resolve("POST", "/api/v1/cart/items").name()).isEqualTo("cart-write");
            assertThat(policies.resolve("GET", "/api/v1/merchant/overview").name()).isEqualTo("merchant-read");
            assertThat(policies.resolve("POST", "/api/v1/merchant/order/ship").name()).isEqualTo("merchant-write");
        }

        @Test
        @DisplayName("空路径返回兜底规则而不是抛异常")
        void blankPathFallsBackToDefault() {
            assertThat(policies.resolve("GET", null)).isSameAs(RateLimitPolicies.DEFAULT);
            assertThat(policies.resolve("GET", "")).isSameAs(RateLimitPolicies.DEFAULT);
        }
    }

    @Nested
    @DisplayName("豁免路径")
    class Exemption {

        @ParameterizedTest(name = "{0} 豁免")
        @ValueSource(strings = {
                "/actuator/health",
                "/actuator/prometheus",
                "/swagger-ui.html",
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/v3/api-docs.yaml",
                "/v3/api-docs/swagger-config",
                "/doc.html",
                "/webjars/springfox.js",
                "/uploads/2026/10/07/a.png",
                "/favicon.ico",
                "/error"
        })
        void shouldBeExempt(String path) {
            assertThat(policies.isExempt(path)).as("%s 应豁免限流", path).isTrue();
        }

        @ParameterizedTest(name = "{0} 不豁免")
        @ValueSource(strings = {
                "/api/v1/products",
                "/api/v1/products/1",
                "/api/v1/auth/login",
                "/api/v1/actuator-like"
        })
        void shouldNotBeExempt(String path) {
            assertThat(policies.isExempt(path)).as("%s 不应豁免限流", path).isFalse();
        }

        @Test
        @DisplayName("业务路径不会被 /actuator/** 之类的模式误伤")
        void businessPathsAreNotExempt() {
            assertThat(RateLimitPolicies.exemptPatterns()).doesNotContain("/api/**");
            for (String path : List.of("/api/v1/orders", "/api/v1/merchant/overview", "/api/v1/files")) {
                assertThat(policies.isExempt(path)).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("层级与降级分配")
    class TierAllocation {

        @Test
        @DisplayName("只有「限流是唯一防线」的接口才付 Redis 往返成本，其余走本地")
        void distributedTierIsReservedForCriticalEndpoints() {
            Set<String> distributed = new HashSet<>();
            for (RateLimitPolicy policy : RateLimitPolicies.rules()) {
                if (policy.tier() == RateLimitTier.DISTRIBUTED) {
                    distributed.add(policy.name());
                }
            }
            assertThat(distributed).containsExactlyInAnyOrder(
                    "auth-login", "auth-sms-login", "auth-sms-code", "auth-register",
                    "auth-reset", "merchant-login", "file-upload");
        }

        @Test
        @DisplayName("分布式规则必须显式声明降级策略：防爆破类接口用 FAIL_CLOSED")
        void distributedPoliciesDeclareFallback() {
            for (RateLimitPolicy policy : RateLimitPolicies.rules()) {
                if (policy.tier() == RateLimitTier.DISTRIBUTED) {
                    assertThat(policy.fallback())
                            .as("%s 必须明确降级语义", policy.name())
                            .isEqualTo(RateLimitFallback.FAIL_CLOSED);
                }
            }
        }

        @Test
        @DisplayName("本地规则一律 FAIL_OPEN：限流组件不该成为新的故障点")
        void localPoliciesFailOpen() {
            for (RateLimitPolicy policy : RateLimitPolicies.rules()) {
                if (policy.tier() == RateLimitTier.LOCAL) {
                    assertThat(policy.fallback()).isEqualTo(RateLimitFallback.FAIL_OPEN);
                }
            }
        }

        @Test
        @DisplayName("只有高成本接口配置了并发上限")
        void onlyExpensiveEndpointsHaveConcurrencyLimit() {
            assertThat(RateLimitPolicies.rules())
                    .filteredOn(policy -> policy.maxConcurrent() > 0)
                    .extracting(RateLimitPolicy::name)
                    .containsExactlyInAnyOrder("file-upload", "trade-submit");
        }

        @Test
        @DisplayName("describe() 能完整表达一条规则，便于直接输出到文档")
        void describeIsInformative() {
            RateLimitPolicy policy = policies.resolve("POST", "/api/v1/auth/login");
            assertThat(policy.describe())
                    .contains("POST", "/api/v1/auth/login", "IP", "10 次 / 60s", "DISTRIBUTED", "FAIL_CLOSED");
        }
    }
}
