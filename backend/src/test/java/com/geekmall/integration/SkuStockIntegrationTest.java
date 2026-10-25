package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.inventory.entity.InvBucket;
import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.entity.Sku;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.mapper.SkuMapper;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 多规格 SKU 端到端集成测试（真实 MySQL + Flyway V17 + 真实交易链路）。
 *
 * <p>覆盖方案验证清单的关键风险：</p>
 * <ul>
 *   <li>多 SKU 各自独立扣减，A 见底不影响 B；B 超卖整体回滚（含已扣桶）；</li>
 *   <li><b>同一订单买两个 SKU 各自扣减 / 回补一次</b> —— 专门盯幂等键
 *       （{@code orderNo + skuId}）漏改导致的「第二个 SKU 被吞掉」；</li>
 *   <li>取消订单回补到<b>原 SKU + 原桶</b>，同单重放不重复扣 / 补；</li>
 *   <li>单 SKU（默认 SKU）商品走通全链路 = 兼容性回归；</li>
 *   <li>三层守恒断言：{@code SUM(桶 WHERE sku) == sku.stock}、
 *       {@code SUM(SKU) == 商品库存}，两层对账查询均无差额。</li>
 * </ul>
 */
class SkuStockIntegrationTest extends AbstractIntegrationTest {

    private static final String BUCKET_API = "/api/v1/merchant/inventory/bucket";

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private InvBucketMapper invBucketMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("多 SKU 独立扣减：A 见底不影响 B；B 超卖整体回滚（含已扣桶）")
    void multiSkuDeductsIndependentlyAndOversellRollsBack() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = saveTwoSkuProduct("多规格独立扣减商品", 5, "2999.00", 3, "3099.00");
        Map<String, Long> skuIds = skuIdsBySpecDesc(productId);
        long skuBlack = skuIds.get("黑色/XL");
        long skuWhite = skuIds.get("白色/XL");
        allocateSkuBuckets(merchantToken, productId, skuBlack, buckets("黑-主仓", 3, "黑-备仓", 2));
        allocateSkuBuckets(merchantToken, productId, skuWhite, buckets("白-主仓", 2, "白-备仓", 1));

        // A（黑色）买空 5 件：跨两个桶扣完
        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        long cartA = addToCart(buyerToken, productId, skuBlack, 5);
        assertThat(submitOrder(buyerToken, addressId, List.of(cartA))).isNotBlank();

        assertThat(skuMapper.selectById(skuBlack).getStock()).as("A 买空").isZero();
        assertThat(skuMapper.selectById(skuWhite).getStock()).as("A 见底不影响 B").isEqualTo(3);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(3);
        assertConservation(productId);

        // B（白色）要 5 件但只有 3：跨桶扣完仍不足 → 整体回滚（含已扣的桶）
        String buyer2 = registerAndLogin(randomPhone());
        long address2 = createAddress(buyer2);
        long cartB = addToCart(buyer2, productId, skuWhite, 5);
        Map<String, Object> body = new HashMap<>();
        body.put("addressId", address2);
        body.put("cartItemIds", List.of(cartB));
        body.put("requestId", UUID.randomUUID().toString());
        JsonNode response = post("/api/v1/trade/orders", body, buyer2);
        assertThat(response.get("code").asInt()).as("B 超卖应失败：%s", response).isNotZero();

        assertThat(skuMapper.selectById(skuWhite).getStock()).as("超卖整体回滚后 B 不变").isEqualTo(3);
        JsonNode balance = assertSuccess(get(BUCKET_API + "/balance?productId=" + productId, merchantToken));
        assertThat(bucketStock(balance, "白-主仓")).as("已扣的桶一并回滚").isEqualTo(2);
        assertThat(bucketStock(balance, "白-备仓")).isEqualTo(1);
        assertThat(bucketStock(balance, "黑-主仓")).isZero();
        assertConservation(productId);
    }

    @Test
    @DisplayName("同一订单买两个 SKU：各自扣减 / 回补一次，同单重放不重复扣补")
    void sameOrderTwoSkusDeductAndRestoreOnce() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = saveTwoSkuProduct("同单双规格商品", 6, "100.00", 4, "120.00");
        Map<String, Long> skuIds = skuIdsBySpecDesc(productId);
        long skuBlack = skuIds.get("黑色/XL");
        long skuWhite = skuIds.get("白色/XL");
        allocateSkuBuckets(merchantToken, productId, skuBlack, buckets("黑-仓", 6));
        allocateSkuBuckets(merchantToken, productId, skuWhite, buckets("白-仓", 4));

        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        long cartA = addToCart(buyerToken, productId, skuBlack, 2);
        long cartB = addToCart(buyerToken, productId, skuWhite, 3);
        String orderNo = submitOrder(buyerToken, addressId, List.of(cartA, cartB));

        // 幂等键 = (单号, SKU)：两个 SKU 必须各自扣减一次，不能被吞成只扣一次
        assertThat(skuMapper.selectById(skuBlack).getStock()).as("黑色扣减一次").isEqualTo(4);
        assertThat(skuMapper.selectById(skuWhite).getStock()).as("白色扣减一次").isEqualTo(1);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(5);
        JsonNode logs = assertSuccess(get(BUCKET_API + "/logs?orderNo=" + orderNo, merchantToken));
        assertThat(logs.get("total").asLong()).as("两个 SKU 各留一条出库流水").isEqualTo(2);
        assertConservation(productId);

        // 取消：各回补一次并还回原 SKU + 原桶
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "幂等测试"), buyerToken));
        assertThat(skuMapper.selectById(skuBlack).getStock()).isEqualTo(6);
        assertThat(skuMapper.selectById(skuWhite).getStock()).isEqualTo(4);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(10);
        JsonNode balance = assertSuccess(get(BUCKET_API + "/balance?productId=" + productId, merchantToken));
        assertThat(bucketStock(balance, "黑-仓")).isEqualTo(6);
        assertThat(bucketStock(balance, "白-仓")).isEqualTo(4);

        // 同单重放：重复回滚不得二次回补
        assertSuccess(post(BUCKET_API + "/rollback?orderNo=" + orderNo, null, merchantToken));
        assertSuccess(post(BUCKET_API + "/rollback?orderNo=" + orderNo, null, merchantToken));
        assertThat(skuMapper.selectById(skuBlack).getStock()).as("重放不得二次回补").isEqualTo(6);
        assertThat(skuMapper.selectById(skuWhite).getStock()).isEqualTo(4);

        // 同单重放：按原单号再出库不得二次扣减（幂等返回既有结果）
        JsonNode replay = assertSuccess(post(
                BUCKET_API + "/deduct",
                Map.of("productId", productId, "skuId", skuBlack, "qty", 2, "orderNo", orderNo),
                merchantToken));
        assertThat(replay.get("deductedQty").asInt()).isEqualTo(2);
        assertThat(skuMapper.selectById(skuBlack).getStock()).as("重放不得二次扣减").isEqualTo(6);
        assertConservation(productId);
    }

    @Test
    @DisplayName("取消订单回补到原 SKU + 原桶（跨桶扣减后精确还原）")
    void cancelRestoresToOriginalSkuAndBuckets() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = saveTwoSkuProduct("回补原桶商品", 5, "88.00", 3, "99.00");
        Map<String, Long> skuIds = skuIdsBySpecDesc(productId);
        long skuBlack = skuIds.get("黑色/XL");
        long skuWhite = skuIds.get("白色/XL");
        allocateSkuBuckets(merchantToken, productId, skuBlack, buckets("黑-主仓", 3, "黑-备仓", 2));
        allocateSkuBuckets(merchantToken, productId, skuWhite, buckets("白-主仓", 3));

        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        // 黑色 4 件 = 主仓 3 + 备仓 1；白色 2 件
        long cartA = addToCart(buyerToken, productId, skuBlack, 4);
        long cartB = addToCart(buyerToken, productId, skuWhite, 2);
        String orderNo = submitOrder(buyerToken, addressId, List.of(cartA, cartB));

        JsonNode afterDeduct = assertSuccess(get(BUCKET_API + "/balance?productId=" + productId, merchantToken));
        assertThat(bucketStock(afterDeduct, "黑-主仓")).isZero();
        assertThat(bucketStock(afterDeduct, "黑-备仓")).isEqualTo(1);
        assertThat(bucketStock(afterDeduct, "白-主仓")).isEqualTo(1);
        assertConservation(productId);

        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "回补原桶"), buyerToken));

        // 回补必须还回「原来那些桶」，而不是随便找桶塞回去
        JsonNode afterCancel = assertSuccess(get(BUCKET_API + "/balance?productId=" + productId, merchantToken));
        assertThat(bucketStock(afterCancel, "黑-主仓")).isEqualTo(3);
        assertThat(bucketStock(afterCancel, "黑-备仓")).isEqualTo(2);
        assertThat(bucketStock(afterCancel, "白-主仓")).isEqualTo(3);
        assertThat(skuMapper.selectById(skuBlack).getStock()).isEqualTo(5);
        assertThat(skuMapper.selectById(skuWhite).getStock()).isEqualTo(3);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(8);
        assertConservation(productId);
    }

    @Test
    @DisplayName("单 SKU（默认 SKU）商品走通全链路（兼容性回归：加购不传 skuId）")
    void singleDefaultSkuProductRunsFullChain() {
        // 模拟 V17 回填的默认 SKU：sku_code = SKU-{id}-D、spec_keys 空、spec_desc 取商品 spec
        Product product = createProductWithDefaultSku("单规格默认 SKU 商品", 10, "199.00");
        long skuId = defaultSkuIdOf(product.getId());

        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        // 旧客户端形态：加购不传 skuId，应落到默认 SKU 并全链路按 SKU 记账
        long cartItemId = assertSuccess(
                        post("/api/v1/cart/items", Map.of("productId", product.getId(), "qty", 2), buyerToken))
                .asLong();
        String orderNo = submitOrder(buyerToken, addressId, List.of(cartItemId));

        List<OrderItem> items =
                orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getSkuId()).as("订单明细应记到默认 SKU").isEqualTo(skuId);
        assertThat(items.get(0).getSpec()).as("spec 语义 = SKU 的 spec_desc 快照").isEqualTo("标准版");
        assertThat(skuMapper.selectById(skuId).getStock()).isEqualTo(8);
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(8);
        assertConservation(product.getId());

        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "兼容回归"), buyerToken));
        assertThat(skuMapper.selectById(skuId).getStock()).isEqualTo(10);
        assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(10);
        assertConservation(product.getId());
    }

    @Test
    @DisplayName("三层守恒：混合操作后两层对账查询（桶 vs SKU、SUM(SKU) vs 商品）均无差额")
    void reconcileQueriesReportNoDrift() {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = saveTwoSkuProduct("两层对账商品", 5, "66.00", 3, "77.00");
        Map<String, Long> skuIds = skuIdsBySpecDesc(productId);
        allocateSkuBuckets(merchantToken, productId, skuIds.get("黑色/XL"), buckets("黑-主仓", 3, "黑-备仓", 2));
        allocateSkuBuckets(merchantToken, productId, skuIds.get("白色/XL"), buckets("白-主仓", 3));

        String buyerToken = registerAndLogin(randomPhone());
        long addressId = createAddress(buyerToken);
        long cartA = addToCart(buyerToken, productId, skuIds.get("黑色/XL"), 2);
        long cartB = addToCart(buyerToken, productId, skuIds.get("白色/XL"), 1);
        String orderNo = submitOrder(buyerToken, addressId, List.of(cartA, cartB));
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "对账测试"), buyerToken));

        assertConservation(productId);
        assertThat(invBucketMapper.selectInconsistentSkus())
                .as("SKU 层：桶合计 ≠ SKU 库存")
                .isEmpty();
        assertThat(invBucketMapper.selectInconsistentProducts())
                .as("商品层：SUM(SKU) ≠ 商品库存")
                .isEmpty();
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 保存一个两规格商品（黑色/XL、白色/XL），返回商品 ID。 */
    private long saveTwoSkuProduct(String name, int blackStock, String blackPrice, int whiteStock, String whitePrice) {
        String merchantToken = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("cat", "集成测试分类");
        body.put("brand", "极客严选");
        body.put("price", new BigDecimal(blackPrice));
        body.put("stock", blackStock + whiteStock);
        body.put("safeStock", 1);
        body.put("status", "on");
        body.put("spec", "默认规格");
        body.put(
                "specDimensions",
                List.of(
                        Map.of(
                                "name",
                                "颜色",
                                "values",
                                List.of(
                                        Map.of("value", "黑色", "image", "https://img/black.png"),
                                        Map.of("value", "白色", "image", "https://img/white.png"))),
                        Map.of("name", "尺寸", "values", List.of(Map.of("value", "XL")))));
        body.put(
                "skus",
                List.of(
                        skuRow(Map.of("颜色", "黑色", "尺寸", "XL"), blackPrice, blackStock),
                        skuRow(Map.of("颜色", "白色", "尺寸", "XL"), whitePrice, whiteStock)));
        return assertSuccess(post("/api/v1/merchant/product/save", body, merchantToken))
                .asLong();
    }

    private Map<String, Object> skuRow(Map<String, String> specValues, String price, int stock) {
        Map<String, Object> row = new HashMap<>();
        row.put("specValues", specValues);
        row.put("price", new BigDecimal(price));
        row.put("stock", stock);
        row.put("cost", new BigDecimal("100.00"));
        return row;
    }

    /** 详情接口回读 SKU：{@code specDesc -> skuId}。 */
    private Map<String, Long> skuIdsBySpecDesc(long productId) {
        JsonNode skus =
                assertSuccess(get("/api/v1/products/" + productId, null)).get("skus");
        Map<String, Long> result = new LinkedHashMap<>();
        for (JsonNode sku : skus) {
            result.put(sku.get("specDesc").asText(), sku.get("id").asLong());
        }
        return result;
    }

    /** 给指定 SKU 分桶：维度值 → 数量（入参顺序即建桶顺序，FIFO 出库按此先后）。 */
    private void allocateSkuBuckets(String merchantToken, long productId, long skuId, Map<String, Integer> buckets) {
        List<Map<String, Object>> items = new ArrayList<>();
        buckets.forEach((dimensionValue, qty) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("dimensionValue", dimensionValue);
            item.put("qty", qty);
            items.add(item);
        });
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("skuId", skuId);
        body.put("dimension", "BATCH");
        body.put("items", items);
        assertSuccess(post(BUCKET_API + "/allocate", body, merchantToken));
    }

    /** 有序的桶布局（保持入参顺序，供 FIFO 断言依赖）。 */
    private Map<String, Integer> buckets(String name1, int qty1, String name2, int qty2) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(name1, qty1);
        map.put(name2, qty2);
        return map;
    }

    /** 单桶布局。 */
    private Map<String, Integer> buckets(String name, int qty) {
        return Map.of(name, qty);
    }

    /** 模拟 V17 回填形态：商品 + 一条默认 SKU（spec_keys 空、sku_code = SKU-{id}-D）。 */
    private Product createProductWithDefaultSku(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("sku-integration-test");
        product.setParentKey("integration");
        product.setCover("https://img/test/" + title + ".png");
        product.setSpec("标准版");
        product.setPrice(new BigDecimal(price));
        product.setOldPrice(new BigDecimal(price).add(new BigDecimal("50.00")));
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

        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setShopId(DEMO_SHOP_ID);
        sku.setSkuCode("SKU-" + product.getId() + "-D");
        sku.setSpecDesc("标准版");
        sku.setSpecKeys("");
        sku.setPrice(new BigDecimal(price));
        sku.setCost(new BigDecimal("100.00"));
        sku.setStock(stock);
        sku.setSafeStock(5);
        sku.setStatus(1);
        skuMapper.insert(sku);
        return product;
    }

    private long defaultSkuIdOf(long productId) {
        return skuMapper
                .selectOne(new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getProductId, productId)
                        .orderByAsc(Sku::getId)
                        .last("limit 1"))
                .getId();
    }

    /**
     * 三层守恒断言：{@code SUM(桶 WHERE sku_id) == pms_sku.stock}（已分桶的 SKU）、
     * {@code SUM(pms_sku.stock) == pms_product.stock}。
     */
    private void assertConservation(long productId) {
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>().eq(Sku::getProductId, productId));
        assertThat(skus).as("商品应有 SKU").isNotEmpty();
        int skuTotal = 0;
        for (Sku sku : skus) {
            boolean bucketed = invBucketMapper.selectCount(
                            new LambdaQueryWrapper<InvBucket>().eq(InvBucket::getSkuId, sku.getId()))
                    > 0;
            if (bucketed) {
                assertThat(invBucketMapper.sumStockBySku(sku.getId()))
                        .as("SKU 层守恒：SUM(桶) == SKU 库存，skuId=%s", sku.getId())
                        .isEqualTo(sku.getStock());
            }
            skuTotal += sku.getStock() == null ? 0 : sku.getStock();
        }
        assertThat(productMapper.selectById(productId).getStock())
                .as("商品层守恒：SUM(SKU 库存) == 商品库存")
                .isEqualTo(skuTotal);
    }

    private int bucketStock(JsonNode balance, String dimensionValue) {
        for (JsonNode bucket : balance.get("buckets")) {
            if (dimensionValue.equals(bucket.get("dimensionValue").asText())) {
                return bucket.get("stock").asInt();
            }
        }
        return 0;
    }

    private long createAddress(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "SKU 测试收货人");
        body.put("phone", "13800000002");
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detail", "科技园路 2 号");
        body.put("isDefault", true);
        return assertSuccess(post("/api/v1/user/addresses", body, token)).asLong();
    }

    private long addToCart(String token, long productId, long skuId, int qty) {
        return assertSuccess(
                        post("/api/v1/cart/items", Map.of("productId", productId, "skuId", skuId, "qty", qty), token))
                .asLong();
    }

    private String submitOrder(String token, long addressId, List<Long> cartItemIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", cartItemIds);
        body.put("shippingType", "standard");
        body.put("payMethod", "wechat");
        body.put("remark", "SKU 集成测试订单");
        body.put("requestId", UUID.randomUUID().toString());
        return assertSuccess(post("/api/v1/trade/orders", body, token)).asText();
    }
}
