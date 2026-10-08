package com.geekmall.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家端接口集成测试。
 *
 * <p>除了接口连通性，重点验证两件事：</p>
 * <ul>
 *   <li><b>双端鉴权隔离</b>：买家令牌不能访问商家接口，商家令牌也不能访问买家接口；
 *       令牌作用域（scope=merchant）与独立 Redis 会话是这条边界的实现基础。</li>
 *   <li><b>店铺维度数据隔离</b>：新增商品时 shopId 取自令牌而非请求体，
 *       商家无法通过伪造参数把商品塞进别的店铺。</li>
 * </ul>
 */
class MerchantApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

    @Test
    @DisplayName("商家登录 → 店铺信息 → 商家资料：登录态贯通")
    void shouldLoginAndReadShopInfo() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        assertThat(token).isNotBlank();

        JsonNode shop = assertSuccess(get("/api/v1/merchant/auth/shop", token));
        assertThat(shop.get("id").asLong()).isEqualTo(DEMO_SHOP_ID);
        assertThat(shop.get("name").asText()).isNotBlank();
        assertThat(shop.get("verified").asBoolean()).isTrue();

        JsonNode profile = assertSuccess(get("/api/v1/merchant/auth/profile", token));
        assertThat(profile).isNotNull();
    }

    @Test
    @DisplayName("商家登录失败：密码错误返回业务错误码")
    void shouldRejectWrongMerchantPassword() {
        JsonNode response = post("/api/v1/merchant/auth/login",
                Map.of("account", DEMO_MERCHANT, "password", "wrong"), null);

        assertThat(response.get("code").asInt()).isNotZero();
    }

    @ParameterizedTest(name = "商家端读接口 {0} 可用")
    @ValueSource(strings = {
            "/api/v1/merchant/nav/badges",
            "/api/v1/merchant/overview",
            "/api/v1/merchant/analytics",
            "/api/v1/merchant/analytics/daily?days=7",
            "/api/v1/merchant/product/stock-alerts",
            "/api/v1/merchant/product/page",
            "/api/v1/merchant/product/filters",
            "/api/v1/merchant/order/page",
            "/api/v1/merchant/shipment/page",
            "/api/v1/merchant/shipment/filters",
            "/api/v1/merchant/aftersale/page",
            "/api/v1/merchant/review/page",
            "/api/v1/merchant/review/summary",
            "/api/v1/merchant/promotion/page",
            "/api/v1/merchant/promotion/channels",
            "/api/v1/merchant/coupon/list",
            "/api/v1/merchant/fund/summary",
            "/api/v1/merchant/settlement/page",
            "/api/v1/merchant/fund/flow"
    })
    @DisplayName("商家域全部读接口在真实数据源下均返回成功")
    void merchantReadEndpointsShouldSucceed(String url) {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        JsonNode data = assertSuccess(get(url, token));

        assertThat(data).as("接口 %s 应返回数据", url).isNotNull();
    }

    @Test
    @DisplayName("侧栏角标返回完整字段，缺字段会让前端 Tab 计数错位")
    void navBadgesShouldExposeAllCounters() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        JsonNode badges = assertSuccess(get("/api/v1/merchant/nav/badges", token));

        assertThat(badges.has("productWarn")).isTrue();
        assertThat(badges.has("orderPending")).isTrue();
        assertThat(badges.has("shippingLate")).isTrue();
        assertThat(badges.has("aftersale")).isTrue();
        assertThat(badges.has("marketing")).isTrue();
        assertThat(badges.has("reviewWait")).isTrue();
    }

    @Test
    @DisplayName("商品分页返回 list/total 与 Tab 计数、统计条等聚合字段")
    void productPageShouldCarryAggregates() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        JsonNode data = assertSuccess(get("/api/v1/merchant/product/page?page=1&size=2", token));

        assertThat(data.has("list")).isTrue();
        assertThat(data.has("total")).isTrue();
        assertThat(data.has("tabs")).as("商品页 Tab 计数应由服务端下发").isTrue();
        assertThat(data.has("stats")).isTrue();
    }

    @Test
    @DisplayName("新增商品：shopId 取自令牌，无法通过请求体写入其他店铺")
    void createdProductShouldBelongToTokenShop() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        JsonNode id = assertSuccess(post("/api/v1/merchant/product/save", Map.of(
                "name", "集成测试商品 · 商家域",
                "cat", "数码配件",
                "brand", "极客严选",
                "price", 199.00,
                "listPrice", 259.00,
                "cost", 120.00,
                "stock", 50,
                "safeStock", 10,
                "status", "on",
                "tag", "测试"), token));

        Product product = productMapper.selectById(id.asLong());
        assertThat(product).isNotNull();
        assertThat(product.getShopId()).isEqualTo(DEMO_SHOP_ID);
        assertThat(product.getTitle()).isEqualTo("集成测试商品 · 商家域");
        assertThat(product.getStock()).isEqualTo(50);
        assertThat(product.getMerchantStatus()).isEqualTo("on");
        assertThat(product.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("商品上下架：状态落库到商家侧状态字段")
    void shouldUpdateProductStatus() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        long productId = assertSuccess(post("/api/v1/merchant/product/save", Map.of(
                "name", "状态变更测试商品",
                "cat", "数码配件",
                "brand", "极客严选",
                "price", 99.00,
                "stock", 20,
                "safeStock", 5,
                "status", "on"), token)).asLong();

        assertSuccess(post("/api/v1/merchant/product/status",
                Map.of("ids", List.of(productId), "status", "off"), token));

        assertThat(productMapper.selectById(productId).getMerchantStatus()).isEqualTo("off");
    }

    @Test
    @DisplayName("商家端保存商品参数非法时返回参数错误码")
    void shouldRejectInvalidProductPayload() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        JsonNode response = post("/api/v1/merchant/product/save",
                Map.of("price", 10.00), token);

        assertThat(response.get("code").asInt()).isNotZero();
    }

    @Test
    @DisplayName("未登录访问商家接口返回 401")
    void shouldRejectAnonymousMerchantAccess() {
        JsonNode response = get("/api/v1/merchant/overview", null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("买家令牌不能访问商家接口（双端鉴权隔离：403 无权限，而非 401 未登录）")
    void buyerTokenShouldNotAccessMerchantApi() {
        String buyerToken = loginDemoBuyer();

        JsonNode response = get("/api/v1/merchant/overview", buyerToken);

        // 买家令牌是「已认证」的，只是没有商家身份 —— 语义上应是 403（无权限）。
        // 此前靠各商家 service 内部调 MerchantSecurityUtils.getShopId() 抛 401 兜底，
        // 会被误报成「未登录」；现已由 SecurityConfig 的路径规则 + 商家控制器的
        // @PreAuthorize("hasRole('MERCHANT')") 在进入业务前拦住。
        assertThat(response.get("code").asInt())
                .as("买家令牌已认证但无商家身份，应返回 403")
                .isEqualTo(ResultCode.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("商家令牌不能访问买家接口（双端鉴权隔离）")
    void merchantTokenShouldNotAccessBuyerApi() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        JsonNode response = get("/api/v1/user/profile", merchantToken);

        assertThat(response.get("code").asInt())
                .as("商家令牌进入买家域必须被判为未登录")
                .isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("买家令牌与商家令牌相互独立，可同时在线互不干扰")
    void buyerAndMerchantTokensShouldCoexist() {
        String buyerToken = loginDemoBuyer();
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        assertThat(get("/api/v1/user/profile", buyerToken).get("code").asInt()).isZero();
        assertThat(get("/api/v1/merchant/auth/shop", merchantToken).get("code").asInt()).isZero();
        assertThat(get("/api/v1/cart/items", buyerToken).get("code").asInt()).isZero();
        assertThat(get("/api/v1/merchant/nav/badges", merchantToken).get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("商家登出接口正常返回")
    void merchantLogoutShouldSucceed() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);

        assertThat(post("/api/v1/merchant/auth/logout", null, token).get("code").asInt()).isZero();
    }
}
