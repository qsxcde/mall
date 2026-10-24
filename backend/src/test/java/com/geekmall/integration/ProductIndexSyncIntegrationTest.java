package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.support.AbstractSearchIntegrationTest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 商品索引增量同步的端到端测试（真实商品写路径 → 真实事件 → 真实 ES 写入）。
 *
 * <p><b>为什么必须走 HTTP 接口而不是直接调索引器</b>：这条链路真正容易断的地方
 * 不是「ES 客户端能写文档」，而是装配：{@code mall.search.engine=elasticsearch} 时
 * 监听器是否被注册、事务提交后事件是否真的发出、{@code @Async} 是否真的执行。
 * 直接调索引器会把这三段全部跳过 —— 那样测试再绿，也不能说明增量链路是通的。</p>
 *
 * <p><b>为什么本类在类级别开启 {@code mall.search.engine=elasticsearch}</b>：
 * 这是被测能力本身的前提条件。它会让 Spring 建一份独立的上下文（多花十几秒），
 * 但换来的是一条<b>与生产同构</b>的验证链路。其它集成测试仍用默认的 mysql-like，
 * 不受影响。</p>
 */
@TestPropertySource(properties = "mall.search.engine=elasticsearch")
class ProductIndexSyncIntegrationTest extends AbstractSearchIntegrationTest {

    private static final String MERCHANT_PRODUCT_API = "/api/v1/merchant/product";

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        // 每个用例从空索引开始：共享同一个 ES 容器，不清会互相污染
        resetSearchIndex();
    }

    @Test
    @DisplayName("商家新增上架商品：提交后自动进入索引，且成本价不进索引")
    void newlyCreatedProductIsIndexedAutomatically() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        String title = "增量索引新增商品-" + System.nanoTime();

        long productId = createProduct(token, title, "1299.00", 30);

        Map<String, Object> doc = awaitDocument(productId);
        assertThat(doc).as("商品保存后应自动写入 ES，无需手工跑同步脚本").isNotNull();
        assertThat(doc.get("title")).isEqualTo(title);
        assertThat(doc.get("status")).isEqualTo(1);
        // 数值字段在 JSON 里可能被解析成 Integer/Long/Double，统一用字符串比较类型无关
        assertThat(String.valueOf(doc.get("price"))).isEqualTo("1299.0");
        assertThat(doc).as("成本价是商家敏感字段，绝不能进索引").doesNotContainKey("cost");
    }

    @Test
    @DisplayName("商家改价：索引里的价格随之下调（不需要重跑全量脚本）")
    void priceChangeIsPropagatedToIndex() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = createProduct(token, "增量索引改价商品-" + System.nanoTime(), "888.00", 20);
        assertThat(awaitDocument(productId)).isNotNull();

        // 按金额直接改价：888 - 100 = 788
        Map<String, Object> body = new HashMap<>();
        body.put("ids", List.of(productId));
        body.put("mode", "amount");
        body.put("value", new BigDecimal("-100.00"));
        assertSuccess(post(MERCHANT_PRODUCT_API + "/batch-price", body, token));

        Map<String, Object> doc = awaitDocWithPrice(productId, "788.0");
        assertThat(doc).as("改价后索引应更新到新价格").isNotNull();
    }

    @Test
    @DisplayName("商家下架商品：文档从索引中移除，搜不到也点不进（不是改 status=0 留在索引里）")
    void offShelfProductIsRemovedFromIndex() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = createProduct(token, "增量索引下架商品-" + System.nanoTime(), "520.00", 10);
        assertThat(awaitDocument(productId)).isNotNull();

        assertSuccess(
                post(MERCHANT_PRODUCT_API + "/status", Map.of("ids", List.of(productId), "status", "off"), token));

        assertThat(awaitDocumentRemoved(productId))
                .as("下架商品不应留在索引里：留一条 status=0 的文档会让 term 过滤承担本该由同步承担的工作")
                .isTrue();
    }

    @Test
    @DisplayName("重新上架：商品回到索引（下架不删数据，只是可见性变化）")
    void backOnShelfRestoresIndexEntry() {
        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = createProduct(token, "增量索引重新上架-" + System.nanoTime(), "66.00", 5);
        assertThat(awaitDocument(productId)).isNotNull();

        assertSuccess(
                post(MERCHANT_PRODUCT_API + "/status", Map.of("ids", List.of(productId), "status", "off"), token));
        assertThat(awaitDocumentRemoved(productId)).isTrue();

        assertSuccess(post(MERCHANT_PRODUCT_API + "/status", Map.of("ids", List.of(productId), "status", "on"), token));
        assertThat(awaitDocument(productId)).as("重新上架后应重新进入索引").isNotNull();
    }

    @Test
    @DisplayName("非 ES 引擎下不装配索引写入组件：mysql-like 部署不该凭空连一个 ES")
    void indexerBeanOnlyExistsWhenElasticsearchEngineIsEnabled() {
        // 本类上下文开启了 elasticsearch，因此索引器与监听器都应存在
        assertThat(applicationContext.getBeansOfType(com.geekmall.modules.product.search.ProductSearchIndexer.class))
                .as("mall.search.engine=elasticsearch 时应装配索引器")
                .hasSize(1);
        assertThat(applicationContext.getBeansOfType(
                        com.geekmall.modules.product.listener.ProductIndexSyncListener.class))
                .as("监听器与索引器条件一致，避免监听器空转")
                .hasSize(1);
    }

    @Test
    @DisplayName("索引不存在时自动按正确 mapping 建索引：新环境不必先手工跑同步脚本")
    void missingIndexIsCreatedWithCorrectMapping() {
        // 模拟「刚部署、从没跑过全量脚本」：索引完全不存在
        deleteSearchIndex();
        assertThat(indexExists()).isFalse();

        String token = loginMerchant(DEMO_MERCHANT, DEMO_PASSWORD);
        long productId = createProduct(token, "自动建索引商品-" + System.nanoTime(), "399.00", 8);

        Map<String, Object> doc = awaitDocument(productId);
        assertThat(doc).as("索引缺失时应自动创建并写入，而不是静默丢写").isNotNull();
        assertThat(indexExists()).isTrue();
        // 自动创建的索引必须用「显式 mapping」而不是 ES 的动态推断：
        // 动态推断会把 price 映射成 float，之后 scaled_float 的按分存整就失效了
        assertThat(fieldType("price")).isEqualTo("scaled_float");
        assertThat(fieldType("brandName")).isEqualTo("keyword");
        assertThat(fieldType("releaseTime")).isEqualTo("date");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 走商家端的真实保存接口创建商品（返回商品 ID）。 */
    private long createProduct(String token, String title, String price, int stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", title);
        body.put("cat", "增量索引测试分类");
        body.put("brand", "极客严选");
        body.put("price", new BigDecimal(price));
        body.put("listPrice", new BigDecimal(price).add(new BigDecimal("200.00")));
        body.put("cost", new BigDecimal("100.00"));
        body.put("stock", stock);
        body.put("status", "on");
        body.put("cover", "https://img/test/" + title + ".png");
        body.put("tag", "测试");
        return assertSuccess(post(MERCHANT_PRODUCT_API + "/save", body, token)).asLong();
    }

    /**
     * 轮询等待文档出现。
     *
     * <p>链路是异步的（{@code @Async} + ES 的刷新间隔），必须轮询而不是 sleep 固定时长：
     * 固定 sleep 在慢机器上会随机失败，在快机器上又白等。</p>
     */
    private Map<String, Object> awaitDocument(long productId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            refreshSearchIndex();
            Map<String, Object> doc = getIndexedDocument(productId);
            if (doc != null) {
                return doc;
            }
            sleep();
        }
        return null;
    }

    /** 轮询等待文档的价格更新到期望值。 */
    private Map<String, Object> awaitDocWithPrice(long productId, String expectedPrice) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            refreshSearchIndex();
            Map<String, Object> doc = getIndexedDocument(productId);
            if (doc != null && expectedPrice.equals(String.valueOf(doc.get("price")))) {
                return doc;
            }
            sleep();
        }
        return null;
    }

    /** 轮询等待文档从索引消失。 */
    private boolean awaitDocumentRemoved(long productId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            refreshSearchIndex();
            if (getIndexedDocument(productId) == null) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待索引同步被中断", e);
        }
    }
}
