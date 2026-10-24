package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

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
        JsonNode balance = allocate(
                merchantToken,
                product.getId(),
                ruleId,
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
        assertSuccess(post("/api/v1/trade/orders/" + orderNo + "/cancel", Map.of("reason", "分桶测试取消"), buyerToken));

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

    /* ============================ 规则管理端点 ============================ */

    @Nested
    @DisplayName("分桶规则：保存 / 列表 / 启停")
    class RuleEndpoints {

        @Test
        @DisplayName("保存后可列出，按维度与启用状态筛选生效，停用后 enabled 变 0")
        void ruleLifecycleIsQueryableAndToggleable() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("规则管理商品", 50, "88.00");
            String ruleName = "区域分桶-" + product.getId();

            long ruleId = saveRule(token, ruleName, "REGION", "MANUAL");

            // 列表：按维度筛选
            JsonNode byDimension = assertSuccess(get(BUCKET_API + "/rule/list?dimension=REGION", token));
            assertThat(byDimension.isArray()).isTrue();
            JsonNode saved = findByRuleName(byDimension, ruleName);
            assertThat(saved).as("新建的规则应出现在列表里").isNotNull();
            assertThat(saved.get("deductPolicy").asText()).isEqualTo("MANUAL");
            assertThat(saved.get("enabled").asInt()).isEqualTo(1);
            assertThat(saved.get("dimension").asText()).isEqualTo("REGION");

            // 停用 → enabled=0，可按 enabled 反查
            assertSuccess(post(BUCKET_API + "/rule/status?ruleId=" + ruleId + "&enabled=0", null, token));
            JsonNode disabled = assertSuccess(get(BUCKET_API + "/rule/list?enabled=0", token));
            JsonNode disabledRule = findByRuleName(disabled, ruleName);
            assertThat(disabledRule).as("停用后应出现在 enabled=0 的列表里").isNotNull();
            assertThat(disabledRule.get("enabled").asInt()).isZero();

            // 重新启用
            assertSuccess(post(BUCKET_API + "/rule/status?ruleId=" + ruleId + "&enabled=1", null, token));
            JsonNode reEnabled = assertSuccess(get(BUCKET_API + "/rule/list?enabled=1", token));
            assertThat(findByRuleName(reEnabled, ruleName).get("enabled").asInt())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("规则名重复：同店铺内必须唯一，第二次保存被拒绝")
        void duplicateRuleNameIsRejected() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            String ruleName = "重名规则-" + System.nanoTime();
            saveRule(token, ruleName, "WAREHOUSE", "FIFO");

            Map<String, Object> body = Map.of("ruleName", ruleName, "dimension", "WAREHOUSE");
            JsonNode response = post(BUCKET_API + "/rule/save", body, token);
            assertThat(response.get("code").asInt())
                    .as("同店铺内规则名应唯一：%s", response)
                    .isNotZero();
        }
    }

    /* ============================ 库存分配端点 ============================ */

    @Nested
    @DisplayName("库存分配：明细分配 / 自动均分")
    class AllocateEndpoints {

        @Test
        @DisplayName("按维度值自动均分：除不尽时余数全部落在第一个桶，总量守恒")
        void autoSplitKeepsTotalAndPutsRemainderFirst() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            // 100 分到 3 个桶：34 / 33 / 33，余数 1 落在第一个桶
            Product product = createOnShelfProduct("自动均分商品", 100, "12.00");
            long ruleId = saveRule(token, "均分规则-" + product.getId(), "WAREHOUSE", "FIFO");

            JsonNode balance = assertSuccess(post(
                    BUCKET_API + "/auto-split?productId=" + product.getId() + "&ruleId=" + ruleId
                            + "&values=WH-A,WH-B,WH-C",
                    null,
                    token));

            assertThat(balance.get("consistent").asBoolean()).isTrue();
            assertThat(balance.get("bucketStockTotal").asInt()).isEqualTo(100);
            assertThat(balance.get("bucketCount").asInt()).isEqualTo(3);
            assertThat(bucketStock(balance, "WH-A")).isEqualTo(34);
            assertThat(bucketStock(balance, "WH-B")).isEqualTo(33);
            assertThat(bucketStock(balance, "WH-C")).isEqualTo(33);
        }

        @Test
        @DisplayName("分配数量之和不得超过商品总库存：超额应被拒绝且不改动任何桶")
        void allocateRejectsOverAllocation() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("超额分配商品", 10, "30.00");
            long ruleId = saveRule(token, "超额规则-" + product.getId(), "BATCH", "FIFO");

            Map<String, Object> body = new HashMap<>();
            body.put("productId", product.getId());
            body.put("ruleId", ruleId);
            body.put("items", List.of(bucketItem("BATCH-X", 8, null), bucketItem("BATCH-Y", 8, null)));
            JsonNode response = post(BUCKET_API + "/allocate", body, token);

            assertThat(response.get("code").asInt())
                    .as("8 + 8 > 10 应被拒绝：%s", response)
                    .isNotZero();
            JsonNode balance = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), token));
            assertThat(balance.get("bucketCount").asInt()).as("失败不应留下半个桶").isZero();
            assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(10);
        }
    }

    /* ============================ 查询端点 ============================ */

    @Nested
    @DisplayName("查询：分页 / 报告 / 流水 / 调拨单")
    class QueryEndpoints {

        @Test
        @DisplayName("桶分页：可按维度过滤，只统计有余量的桶时不返回空桶")
        void bucketsArePagedAndFilterable() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("分页查询商品", 30, "45.00");
            long ruleId = saveRule(token, "分页规则-" + product.getId(), "WAREHOUSE", "FIFO");
            allocate(token, product.getId(), ruleId, bucketItem("WH-P1", 10, null), bucketItem("WH-P2", 20, null));

            JsonNode page = assertSuccess(get(
                    BUCKET_API + "/page?productId=" + product.getId() + "&dimension=WAREHOUSE&page=1&size=10", token));
            assertThat(page.get("total").asLong()).isEqualTo(2);
            assertThat(page.get("list").size()).isEqualTo(2);
            for (JsonNode bucket : page.get("list")) {
                assertThat(bucket.get("dimension").asText()).isEqualTo("WAREHOUSE");
                assertThat(bucket.get("productId").asLong()).isEqualTo(product.getId());
            }

            // 关键字过滤：只留 WH-P1
            JsonNode filtered =
                    assertSuccess(get(BUCKET_API + "/page?productId=" + product.getId() + "&keyword=WH-P1", token));
            assertThat(filtered.get("total").asLong()).isEqualTo(1);
            assertThat(filtered.get("list").get(0).get("dimensionValue").asText())
                    .isEqualTo("WH-P1");
        }

        @Test
        @DisplayName("分桶报告：按维度汇总的数量与桶数正确，且与商品总库存对账一致")
        void reportAggregatesByDimension() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("报告商品", 60, "70.00");
            long ruleId = saveRule(token, "报告规则-" + product.getId(), "WAREHOUSE", "FIFO");
            allocate(token, product.getId(), ruleId, bucketItem("WH-R1", 20, null), bucketItem("WH-R2", 40, null));

            JsonNode report = assertSuccess(get(BUCKET_API + "/report?productId=" + product.getId(), token));

            assertThat(report.get("consistent").asBoolean()).isTrue();
            assertThat(report.get("productStock").asInt()).isEqualTo(60);
            assertThat(report.get("bucketStockTotal").asInt()).isEqualTo(60);
            assertThat(report.get("delta").asInt()).isZero();
            assertThat(report.get("generatedAt").asText()).isNotBlank();
            assertThat(report.get("dimensionStats").isArray()).isTrue();
            JsonNode warehouseStat = null;
            for (JsonNode stat : report.get("dimensionStats")) {
                if ("WAREHOUSE".equals(stat.get("dimension").asText())) {
                    warehouseStat = stat;
                }
            }
            assertThat(warehouseStat).as("报告应含 WAREHOUSE 维度汇总").isNotNull();
            assertThat(warehouseStat.get("stock").asInt()).isEqualTo(60);
            assertThat(warehouseStat.get("bucketCount").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("不存在的商品：报告与余量都必须报错，而不是返回一份全 0 的假报告")
        void reportOnMissingProductFails() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            long ghostId = 99_999_999L;

            assertThat(post(BUCKET_API + "/balance?productId=" + ghostId, null, token)
                            .get("code")
                            .asInt())
                    .as("不存在的商品不该给出一份 consistent=true 的空报告")
                    .isNotZero();
            assertThat(get(BUCKET_API + "/report?productId=" + ghostId, token)
                            .get("code")
                            .asInt())
                    .isNotZero();
        }
    }

    /* ============================ 调拨 / 合并 ============================ */

    @Nested
    @DisplayName("跨桶操作：调拨与合并")
    class TransferAndMerge {

        @Test
        @DisplayName("调拨：从 A 挪 5 到 B，两桶余量与总量都对，且产生调拨单与两条流水")
        void transferMovesStockBetweenBuckets() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("调拨商品", 40, "150.00");
            long ruleId = saveRule(token, "调拨规则-" + product.getId(), "WAREHOUSE", "FIFO");
            JsonNode balance = allocate(
                    token, product.getId(), ruleId, bucketItem("WH-T1", 15, null), bucketItem("WH-T2", 25, null));
            long fromId = bucketId(balance, "WH-T1");
            long toId = bucketId(balance, "WH-T2");

            String opNo = assertSuccess(post(
                            BUCKET_API + "/transfer",
                            Map.of("fromBucketId", fromId, "toBucketId", toId, "qty", 5, "operator", "集成测试"),
                            token))
                    .asText();
            assertThat(opNo).startsWith("TRF");

            JsonNode after = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), token));
            assertThat(bucketStock(after, "WH-T1")).isEqualTo(10);
            assertThat(bucketStock(after, "WH-T2")).isEqualTo(30);
            // 调拨只是搬动，不改总量，也不该动商品总库存
            assertThat(after.get("bucketStockTotal").asInt()).isEqualTo(40);
            assertThat(after.get("consistent").asBoolean()).isTrue();
            assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(40);

            // 调拨单可查
            JsonNode ops = assertSuccess(get(BUCKET_API + "/operations?productId=" + product.getId(), token));
            assertThat(ops.get("total").asLong()).isEqualTo(1);
            assertThat(ops.get("list").get(0).get("opNo").asText()).isEqualTo(opNo);
            assertThat(ops.get("list").get(0).get("qty").asInt()).isEqualTo(5);

            // 两条流水：TRANSFER_OUT / TRANSFER_IN
            JsonNode outLogs = assertSuccess(get(BUCKET_API + "/logs?bizType=TRANSFER_OUT&orderNo=" + opNo, token));
            JsonNode inLogs = assertSuccess(get(BUCKET_API + "/logs?bizType=TRANSFER_IN&orderNo=" + opNo, token));
            assertThat(outLogs.get("total").asLong()).isEqualTo(1);
            assertThat(inLogs.get("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("调拨余量不足：整体回滚，两桶余量都不变")
        void transferRejectsInsufficientStock() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("调拨不足商品", 20, "60.00");
            long ruleId = saveRule(token, "调拨不足规则-" + product.getId(), "WAREHOUSE", "FIFO");
            JsonNode balance =
                    allocate(token, product.getId(), ruleId, bucketItem("WH-X", 3, null), bucketItem("WH-Y", 17, null));

            JsonNode response = post(
                    BUCKET_API + "/transfer",
                    Map.of(
                            "fromBucketId",
                            bucketId(balance, "WH-X"),
                            "toBucketId",
                            bucketId(balance, "WH-Y"),
                            "qty",
                            10),
                    token);

            assertThat(response.get("code").asInt())
                    .as("来源桶只有 3，调 10 应失败：%s", response)
                    .isNotZero();
            JsonNode after = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), token));
            assertThat(bucketStock(after, "WH-X")).isEqualTo(3);
            assertThat(bucketStock(after, "WH-Y")).isEqualTo(17);
        }

        @Test
        @DisplayName("跨商品调拨：来源桶与目标桶不属于同一商品时拒绝")
        void transferAcrossProductsIsRejected() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product first = createOnShelfProduct("调拨跨商品-A", 10, "20.00");
            Product second = createOnShelfProduct("调拨跨商品-B", 10, "20.00");
            JsonNode firstBalance = allocate(
                    token,
                    first.getId(),
                    saveRule(token, "跨商品规则A-" + first.getId(), "WAREHOUSE", "FIFO"),
                    bucketItem("WH-CA", 10, null));
            JsonNode secondBalance = allocate(
                    token,
                    second.getId(),
                    saveRule(token, "跨商品规则B-" + second.getId(), "WAREHOUSE", "FIFO"),
                    bucketItem("WH-CB", 10, null));

            JsonNode response = post(
                    BUCKET_API + "/transfer",
                    Map.of(
                            "fromBucketId", bucketId(firstBalance, "WH-CA"),
                            "toBucketId", bucketId(secondBalance, "WH-CB"),
                            "qty", 1),
                    token);

            assertThat(response.get("code").asInt())
                    .as("跨商品调拨应被拒绝：%s", response)
                    .isNotZero();
        }

        @Test
        @DisplayName("合并：两个桶合并到指定维度值，总量守恒，来源桶归零")
        void mergeConsolidatesBuckets() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("合并商品", 50, "99.00");
            long ruleId = saveRule(token, "合并规则-" + product.getId(), "BATCH", "FIFO");
            JsonNode balance = allocate(
                    token, product.getId(), ruleId, bucketItem("B-M1", 20, null), bucketItem("B-M2", 30, null));

            String opNo = assertSuccess(post(
                            BUCKET_API + "/merge",
                            Map.of(
                                    "productId", product.getId(),
                                    "sourceBucketIds", List.of(bucketId(balance, "B-M1"), bucketId(balance, "B-M2")),
                                    "targetDimensionValue", "B-MERGED"),
                            token))
                    .asText();
            assertThat(opNo).startsWith("MRG");

            JsonNode after = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), token));
            assertThat(bucketStock(after, "B-MERGED")).isEqualTo(50);
            // 来源桶应已归零（桶记录保留，余量为 0），总量守恒
            assertThat(bucketStock(after, "B-M1")).isZero();
            assertThat(bucketStock(after, "B-M2")).isZero();
            assertThat(after.get("bucketStockTotal").asInt()).isEqualTo(50);
            assertThat(after.get("consistent").asBoolean()).isTrue();
        }
    }

    /* ============================ 出库 / 回滚端点 ============================ */

    @Nested
    @DisplayName("出库与回滚：直接调用桶接口")
    class DeductAndRollback {

        @Test
        @DisplayName("按优先级出库后按单号回滚：余量精确还回原桶，且重复回滚幂等")
        void deductThenRollbackRestoresOriginalBuckets() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("直接出库商品", 30, "18.00");
            long ruleId = saveRule(token, "直接出库规则-" + product.getId(), "EXPIRY", "EXPIRY_FIRST");
            allocate(
                    token,
                    product.getId(),
                    ruleId,
                    bucketItem("2027-06-01", 10, "2027-06-01"),
                    bucketItem("2029-06-01", 20, "2029-06-01"));
            String orderNo = "TEST-DEDUCT-" + System.nanoTime();

            JsonNode deduct = assertSuccess(post(
                    BUCKET_API + "/deduct",
                    Map.of("productId", product.getId(), "qty", 12, "orderNo", orderNo, "operator", "集成测试"),
                    token));
            assertThat(deduct.get("requestedQty").asInt()).isEqualTo(12);
            assertThat(deduct.get("deductedQty").asInt()).isEqualTo(12);
            // 效期优先：先把 2027-06-01 的 10 件扣完，再从 2029-06-01 扣 2 件
            assertThat(deduct.get("details").size()).isEqualTo(2);
            assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(18);

            // 同一单号重复出库：幂等，不重复扣
            JsonNode again = assertSuccess(post(
                    BUCKET_API + "/deduct",
                    Map.of("productId", product.getId(), "qty", 12, "orderNo", orderNo),
                    token));
            assertThat(again.get("deductedQty").asInt()).isEqualTo(12);
            assertThat(productMapper.selectById(product.getId()).getStock())
                    .as("重复调用不得二次扣减")
                    .isEqualTo(18);

            // 回滚：还回原桶
            JsonNode rollback = assertSuccess(post(BUCKET_API + "/rollback?orderNo=" + orderNo, null, token));
            assertThat(rollback.get("deductedQty").asInt()).isEqualTo(12);
            assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(30);
            JsonNode after = assertSuccess(get(BUCKET_API + "/balance?productId=" + product.getId(), token));
            assertThat(bucketStock(after, "2027-06-01")).isEqualTo(10);
            assertThat(bucketStock(after, "2029-06-01")).isEqualTo(20);

            // 重复回滚：幂等，不重复补
            assertSuccess(post(BUCKET_API + "/rollback?orderNo=" + orderNo, null, token));
            assertThat(productMapper.selectById(product.getId()).getStock())
                    .as("重复回滚不得二次补库存")
                    .isEqualTo(30);
        }

        @Test
        @DisplayName("未分桶商品直接出库：退化为商品级扣减，不产生桶流水")
        void deductUnbucketedProductFallsBackToProductLevel() {
            String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
            Product product = createOnShelfProduct("直接出库未分桶", 10, "25.00");
            String orderNo = "TEST-UNBUCKET-" + System.nanoTime();

            JsonNode deduct = assertSuccess(post(
                    BUCKET_API + "/deduct", Map.of("productId", product.getId(), "qty", 4, "orderNo", orderNo), token));

            assertThat(deduct.get("deductedQty").asInt()).isEqualTo(4);
            assertThat(deduct.get("details").size()).as("未分桶时没有桶明细").isZero();
            assertThat(productMapper.selectById(product.getId()).getStock()).isEqualTo(6);
            assertThat(assertSuccess(get(BUCKET_API + "/logs?orderNo=" + orderNo, token))
                            .get("total")
                            .asLong())
                    .isZero();
        }
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private long saveRule(String token, String ruleName, String dimension, String policy) {
        return assertSuccess(post(
                        BUCKET_API + "/rule/save",
                        Map.of(
                                "ruleName", ruleName,
                                "dimension", dimension,
                                "deductPolicy", policy),
                        token))
                .asLong();
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

    /** 取某维度值对应的桶 ID（调拨/合并需要桶 ID 而不是维度值）。 */
    private long bucketId(JsonNode balance, String dimensionValue) {
        for (JsonNode bucket : balance.get("buckets")) {
            if (dimensionValue.equals(bucket.get("dimensionValue").asText())) {
                return bucket.get("id").asLong();
            }
        }
        throw new AssertionError("余量全景里找不到维度值 " + dimensionValue + "：" + balance);
    }

    /** 在规则列表里按名称查找；找不到返回 null，便于断言给出清晰信息。 */
    private JsonNode findByRuleName(JsonNode rules, String ruleName) {
        for (JsonNode rule : rules) {
            if (ruleName.equals(rule.get("ruleName").asText())) {
                return rule;
            }
        }
        return null;
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
        return assertSuccess(post("/api/v1/cart/items", Map.of("productId", productId, "qty", qty), token))
                .asLong();
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
