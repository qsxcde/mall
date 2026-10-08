package com.geekmall.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存分桶端到端集成测试（真实 MySQL + Flyway V11 迁移 + 真实交易链路）。
 *
 * <p>覆盖：分配守恒、交易域按「商品是否分桶」路由、效期优先出库、回滚还回原桶、未分桶商品边界。</p>
 */
class InventoryBucketIntegrationTest extends AbstractIntegrationTest {

    private static final String BUCKET_API = "/api/v1/merchant/inventory/bucket";

    @Autowired
    private ProductMapper productMapper;

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("分桶商品：分配守恒，下单走桶级扣减，取消把数量还回原桶")
    void bucketedProductShouldDeductAndRestoreByBucket() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        Product product = createOnShelfProduct("分桶下单商品", 100, "199.00");

        long ruleId = saveRule(merchantToken, "效期分桶-" + product.getId(), "EXPIRY", "EXPIRY_FIRST");
        JsonNode balance = allocate(merchantToken, product.getId(), ruleId,
                bucketItem("2027-01-01", 20, "2027-01-01"),
                bucketItem("2030-01-01", 30, "2030-01-01"));

        // 分配守恒：20 + 30 + 未分配 50 = 100
        assertThat(balance.get("consistent").asBoolean()).isTrue();
        assertThat(balance.get("bucketStockTotal").asInt()).isEqualTo(100);
        assertThat(balance.get("bucketCount").asInt()).isEqualTo(3);

        // 买家下单 5 件：应走桶级扣减（商品总库存与桶合计同步下降）
        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        long cartItemId = addToCart(buyerToken, product.getId(), 5);
        String orderNo = submitOrder(buyerToken, addressId, cartItemId);

        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(95);
        JsonNode afterDeduct = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), merchantToken));
        assertThat(afterDeduct.get("bucketStockTotal").asInt()).isEqualTo(95);
        assertThat(afterDeduct.get("consistent").asBoolean()).isTrue();
        // 效期优先：先扣效期最近的桶 2027-01-01（20 -> 15）
        assertThat(bucketStock(afterDeduct, "2027-01-01")).isEqualTo(15);
        assertThat(bucketStock(afterDeduct, "2030-01-01")).isEqualTo(30);

        // 审计流水：该单产生 OUTBOUND 记录
        JsonNode logs = assertSuccess(get(BUCKET_API + "/logs?orderNo=" + orderNo, merchantToken));
        assertThat(logs.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(logs.get("list").get(0).get("bizType").asText()).isEqualTo("OUTBOUND");

        // 取消订单：回补应还回「原来那一桶」（2027 -> 20），守恒保持
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel",
                Map.of("reason", "分桶测试取消"), buyerToken));

        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(100);
        JsonNode afterCancel = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), merchantToken));
        assertThat(afterCancel.get("consistent").asBoolean()).isTrue();
        assertThat(bucketStock(afterCancel, "2027-01-01")).isEqualTo(20);
        assertThat(bucketStock(afterCancel, "未分配")).isEqualTo(50);
    }

    @Test
    @DisplayName("桶余量不足：下单失败整体回滚，桶余量与商品库存都不变")
    void shouldRollbackWhenBucketStockInsufficient() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        Product product = createOnShelfProduct("分桶库存不足商品", 3, "99.00");

        long ruleId = saveRule(merchantToken, "批次分桶-" + product.getId(), "BATCH", "FIFO");
        allocate(merchantToken, product.getId(), ruleId, bucketItem("B-ONLY", 3, null));

        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        long cartItemId = addToCart(buyerToken, product.getId(), 5);
        Map<String, Object> body = new HashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", List.of(cartItemId));
        body.put("requestId", UUID.randomUUID().toString());

        JsonNode response = post("/api/v1/trade/orders", body, buyerToken);

        assertThat(response.get("code").asInt()).as("库存不足应失败：%s", response).isNotZero();
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(3);
        JsonNode balance = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), merchantToken));
        assertThat(balance.get("consistent").asBoolean()).isTrue();
        assertThat(bucketStock(balance, "B-ONLY")).isEqualTo(3);
    }

    @Test
    @DisplayName("未分桶商品：仍走原有单行扣减，不产生桶流水（灰度边界）")
    void unallocatedProductShouldKeepLegacyPath() {
        Product product = createOnShelfProduct("未分桶商品", 10, "59.00");
        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        long cartItemId = addToCart(buyerToken, product.getId(), 2);
        String orderNo = submitOrder(buyerToken, addressId, cartItemId);

        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(8);

        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        JsonNode logs = assertSuccess(get(BUCKET_API + "/logs?orderNo=" + orderNo, merchantToken));
        assertThat(logs.get("total").asLong()).isZero();
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private long saveRule(String token, String ruleName, String dimension, String policy) {
        return assertSuccess(post(BUCKET_API + "/rule/save", Map.of(
                "ruleName", ruleName,
                "dimension", dimension,
                "deductPolicy", policy), token)).asLong();
    }

    private JsonNode allocate(String token, long productId, long ruleId, Map<String, Object>... items) {
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("ruleId", ruleId);
        body.put("items", List.of(items));
        return assertSuccess(post(BUCKET_API + "/allocate", body, token));
    }

    private Map<String, Object> bucketItem(String dimensionValue, int qty, String expireDate) {
        Map<String, Object> item = new HashMap<>();
        item.put("dimensionValue", dimensionValue);
        item.put("qty", qty);
        if (expireDate != null) {
            item.put("expireDate", expireDate);
        }
        return item;
    }

    private int bucketStock(JsonNode balance, String dimensionValue) {
        for (JsonNode bucket : balance.get("buckets")) {
            if (dimensionValue.equals(bucket.get("dimensionValue").asText())) {
                return bucket.get("stock").asInt();
            }
        }
        return 0;
    }

    /** 新建一个上架商品，避免与其它测试竞争同一行库存。 */
    private Product createOnShelfProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("integration-test");
        product.setParentKey("integration");
        product.setCover("https://img/test/" + title + ".png");
        product.setSpec("标准版");
        product.setPrice(new BigDecimal(price));
        product.setOldPrice(new BigDecimal(price).add(new BigDecimal("100.00")));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(stock);
        product.setSafeStock(5);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("集成测试分类");
        product.setBrandName("极客严选");
        product.setTags("测试,包邮");
        product.setIsHot(0);
        product.setIsNew(1);
        productMapper.insert(product);
        return product;
    }

    private long createAddress(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "分桶测试收货人");
        body.put("phone", "13800000002");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 2 号");
        body.put("isDefault", true);
        return assertSuccess(post("/api/v1/user/addresses", body, token)).asLong();
    }

    private long addToCart(String token, long productId, int qty) {
        return assertSuccess(post("/api/v1/cart/items",
                Map.of("productId", productId, "qty", qty), token)).asLong();
    }

    private String submitOrder(String token, long addressId, long cartItemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", List.of(cartItemId));
        body.put("shippingType", "standard");
        body.put("payMethod", "wechat");
        body.put("remark", "分桶集成测试订单");
        body.put("requestId", UUID.randomUUID().toString());
        return assertSuccess(post("/api/v1/trade/orders", body, token)).asText();
    }
}
