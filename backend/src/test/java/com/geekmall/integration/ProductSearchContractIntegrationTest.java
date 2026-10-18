package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.search.ElasticProductSearchService;
import com.geekmall.modules.product.search.FulltextProductSearchService;
import com.geekmall.modules.product.search.LikeProductSearchService;
import com.geekmall.modules.product.search.ProductSearchService;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.support.AbstractSearchIntegrationTest;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 商品搜索契约测试：<b>同一组查询条件下，三种实现必须返回一致的命中集合</b>。
 *
 * <p>{@link ProductSearchService} 的接口注释把这条写成硬约定：
 * 「所有实现必须返回同样的输入 → 同样的输出，尤其是 total 的口径要一致，
 * 否则前端分页会错乱，A/B 对比也失去意义」。但没有测试守着它 ——
 * 三种实现的分页、排序、过滤各写一遍，任何一处漂移都不会报错，只会让
 * 某个引擎下的搜索结果悄悄变得和别的引擎不一样。</p>
 *
 * <h3>为什么用「命中集合」而不是「返回顺序」做断言</h3>
 * <p>三者的排序能力本就不同：ES 有相关度打分（{@code _score}），
 * LIKE / FULLTEXT 只能按业务字段排。要求顺序完全一致既不现实也会掩盖真实差异。
 * 因此断言分两层：</p>
 * <ol>
 *   <li><b>硬断言</b>：{@code total}（命中总数）与命中 id 集合必须完全一致 ——
 *       这是分页正确性的基础；</li>
 *   <li><b>软断言</b>：显式指定排序（如 {@code price_asc}）时，三种实现的首条结果也必须一致 ——
 *       因为此时相关度不应参与排序。</li>
 * </ol>
 */
class ProductSearchContractIntegrationTest extends AbstractSearchIntegrationTest {

    /** 测试商品的品牌名，用于隔离本测试造的数据与种子数据。 */
    private static final String BRAND = "契约测试品牌";

    private static final String CATEGORY_KEY = "contract-test-cat";

    private static final String PARENT_KEY = "contract-test-parent";

    @Autowired
    private ProductMapper productMapper;

    /**
     * 三种实现由 {@code @ConditionalOnProperty(mall.search.engine)} 互斥注册，
     * 同一时刻容器里只有一个 ProductSearchService。
     *
     * <p>契约测试却必须让三者<b>同时存在</b>，因此不用注入，而是直接构造：
     * 它们都只依赖 ProductMapper（ES 实现额外要 uris/index）。
     * 这样也顺带验证了「实现不依赖 Spring 容器能力」这一设计约束。</p>
     */
    private LikeProductSearchService likeService;

    private FulltextProductSearchService fulltextService;

    private ProductSearchService esService;

    /** 三种实现，按名称索引，便于断言失败时看出是哪个引擎漂移。 */
    private Map<String, ProductSearchService> engines;

    @BeforeEach
    void setUp() {
        likeService = new LikeProductSearchService(productMapper);
        fulltextService = new FulltextProductSearchService(productMapper);
        esService = new ElasticProductSearchService(productMapper, "http://" + ES.getHttpHostAddress(), INDEX, false);

        engines = Map.of(
                "like", likeService,
                "fulltext", fulltextService,
                "es", esService);
        // 每个用例从空索引开始：共享同一个 ES 容器，不清会互相污染
        resetSearchIndex();
    }

    /* ============================ 契约：total + 命中集合 ============================ */

    @Nested
    @DisplayName("命中总数与 id 集合")
    class HitSet {

        @Test
        @DisplayName("子串命中：三种引擎的 total 与 id 集合完全一致")
        void keywordReturnsSameHitSet() {
            String token = uniqueToken();
            createProducts(token, 6);

            assertEnginesAgree(query(q -> q.setKeyword(token)));
        }

        @Test
        @DisplayName("无匹配关键词：三种引擎都返回空集合且 total=0")
        void noMatchReturnsEmptyForAll() {
            String token = uniqueToken();

            assertEnginesAgree(query(q -> q.setKeyword(token)));
        }

        @Test
        @DisplayName("品牌过滤：三种引擎一致")
        void brandFilterAgrees() {
            String token = uniqueToken();
            createProducts(token, 4);

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            q.setBrand(BRAND);
            assertEnginesAgree(q);
        }

        @Test
        @DisplayName("价格区间过滤：三种引擎一致（边界值包含在内）")
        void priceRangeAgrees() {
            String token = uniqueToken();
            createProducts(token, 6);

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            q.setPriceMin(new BigDecimal("150.00"));
            q.setPriceMax(new BigDecimal("350.00"));
            assertEnginesAgree(q);
        }

        @Test
        @DisplayName("分类过滤（子分类优先于顶级分类）：三种引擎一致")
        void categoryFilterAgrees() {
            String token = uniqueToken();
            createProducts(token, 4);

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            q.setSub(CATEGORY_KEY);
            assertEnginesAgree(q);
        }

        @Test
        @DisplayName("下架商品被三种引擎一致地排除")
        void offShelfProductsAreExcludedEverywhere() {
            String token = uniqueToken();
            List<Product> onShelf = createProducts(token, 3);
            // 再造一个下架商品（同关键词但 status=0）
            Product off = createProduct(token + " 下架", 3, "99.00");
            off.setStatus(0);
            productMapper.updateById(off);
            // ES 侧也要同步下架状态，否则测的是「索引没更新」而不是「引擎过滤」
            indexDocuments(List.of(toEsDocument(off)));

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            Map<String, PageResult<ProductCardVO>> results = runAll(q);

            for (Map.Entry<String, PageResult<ProductCardVO>> e : results.entrySet()) {
                assertThat(e.getValue().getTotal())
                        .as("引擎 %s 不应计入下架商品", e.getKey())
                        .isEqualTo(onShelf.size());
            }
            assertEnginesAgree(q);
        }
    }

    /* ============================ 契约：分页 ============================ */

    @Nested
    @DisplayName("分页")
    class Pagination {

        @Test
        @DisplayName("分页 total 恒等于命中总数，不随 page/pageSize 变化")
        void totalIsStableAcrossPages() {
            String token = uniqueToken();
            createProducts(token, 7);

            ProductQueryDTO page1 = query(x -> x.setKeyword(token));
            page1.setPage(1);
            page1.setPageSize(3);

            Map<String, PageResult<ProductCardVO>> r1 = runAll(page1);
            long expectedTotal = r1.get("like").getTotal();
            assertThat(expectedTotal).isEqualTo(7);

            for (Map.Entry<String, PageResult<ProductCardVO>> e : r1.entrySet()) {
                assertThat(e.getValue().getTotal())
                        .as("引擎 %s 首页 total", e.getKey())
                        .isEqualTo(7);
                assertThat(e.getValue().getList()).as("引擎 %s 首页条数", e.getKey()).hasSize(3);
            }

            ProductQueryDTO page3 = query(x -> x.setKeyword(token));
            page3.setPage(3);
            page3.setPageSize(3);
            Map<String, PageResult<ProductCardVO>> r3 = runAll(page3);
            for (Map.Entry<String, PageResult<ProductCardVO>> e : r3.entrySet()) {
                assertThat(e.getValue().getTotal())
                        .as("引擎 %s 第 3 页的 total 仍应是 7（不是本页条数）", e.getKey())
                        .isEqualTo(7);
                assertThat(e.getValue().getList()).as("引擎 %s 末页条数", e.getKey()).hasSize(1);
            }
        }

        @Test
        @DisplayName("三页拼起来恰好是完整命中集，无重复无遗漏")
        void pagesCoverAllHitsExactlyOnce() {
            String token = uniqueToken();
            createProducts(token, 5);

            for (String engineName : engines.keySet()) {
                ProductSearchService engine = engines.get(engineName);
                List<Long> collected = new java.util.ArrayList<>();
                for (int page = 1; page <= 2; page++) {
                    ProductQueryDTO q = query(x -> x.setKeyword(token));
                    q.setPage(page);
                    q.setPageSize(3);
                    engine.search(q).getList().forEach(vo -> collected.add(vo.getId()));
                }
                assertThat(collected)
                        .as("引擎 %s 分页结果不应重复", engineName)
                        .doesNotHaveDuplicates()
                        .hasSize(5);
            }
        }
    }

    /* ============================ 契约：显式排序 ============================ */

    @Nested
    @DisplayName("显式排序")
    class Sorting {

        @Test
        @DisplayName("price_asc：三种引擎的首条都是最便宜的那件")
        void priceAscIsConsistent() {
            String token = uniqueToken();
            List<Product> created = createProducts(token, 5);
            Product cheapest = created.stream()
                    .min(Comparator.comparing(Product::getPrice))
                    .orElseThrow();

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            q.setSort("price_asc");

            Map<String, PageResult<ProductCardVO>> results = runAll(q);
            for (Map.Entry<String, PageResult<ProductCardVO>> e : results.entrySet()) {
                assertThat(e.getValue().getList())
                        .as("引擎 %s 按价格升序时，首条应是最便宜的商品", e.getKey())
                        .isNotEmpty();
                assertThat(e.getValue().getList().get(0).getId())
                        .as("引擎 %s 的首条", e.getKey())
                        .isEqualTo(cheapest.getId());
            }
        }

        @Test
        @DisplayName("price_desc：三种引擎的首条都是最贵的那件")
        void priceDescIsConsistent() {
            String token = uniqueToken();
            List<Product> created = createProducts(token, 5);
            Product priciest = created.stream()
                    .max(Comparator.comparing(Product::getPrice))
                    .orElseThrow();

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            q.setSort("price_desc");

            for (Map.Entry<String, PageResult<ProductCardVO>> e : runAll(q).entrySet()) {
                assertThat(e.getValue().getList().get(0).getId())
                        .as("引擎 %s 按价格降序时，首条应是最贵的商品", e.getKey())
                        .isEqualTo(priciest.getId());
            }
        }

        @Test
        @DisplayName("sales 排序：三种引擎的首条都是销量最高的那件")
        void salesSortIsConsistent() {
            String token = uniqueToken();
            List<Product> created = createProducts(token, 4);
            Product bestSeller = created.stream()
                    .max(Comparator.comparing(Product::getSales))
                    .orElseThrow();

            ProductQueryDTO q = query(x -> x.setKeyword(token));
            q.setSort("sales");

            for (Map.Entry<String, PageResult<ProductCardVO>> e : runAll(q).entrySet()) {
                assertThat(e.getValue().getList().get(0).getId())
                        .as("引擎 %s 按销量排序时，首条应是销量最高的商品", e.getKey())
                        .isEqualTo(bestSeller.getId());
            }
        }
    }

    /* ============================ 单独的行为约束 ============================ */

    @Nested
    @DisplayName("各实现的独有约束")
    class EngineSpecific {

        /**
         * FULLTEXT 与 LIKE 的语义等价性。
         *
         * <p><b>为什么这条用例不再用「命中数」去验证短语模式</b>：
         * 实测发现 MySQL 8.0 的 ngram 分词器在 {@code MATCH ... AGAINST(?)} <b>参数绑定</b>下，
         * 短语模式与普通 BOOLEAN 模式返回了<b>完全相同的命中集</b>
         * （同一批关键词 or / phrase 结果一致）。我最初构造了「共享 token 的干扰商品」
         * 想区分两者，但注入「去掉双引号」的 bug 后测试<b>仍然全绿</b> —— 那是假绿。</p>
         *
         * <p>因此短语模式的保障拆成两层：</p>
         * <ol>
         *   <li>本用例：命中集必须与 LIKE 完全一致（契约层，任何漂移都会失败）；</li>
         *   <li>{@code FulltextPhraseModeTest}：直接断言传给 SQL 的 phrase 带双引号
         *       （实现层，去掉引号的 bug 会被当场抓住）。</li>
         * </ol>
         */
        @Test
        @DisplayName("FULLTEXT 命中集与 LIKE 完全一致（含共享 token 的干扰商品）")
        void fulltextMatchesLikeSemantics() {
            String token = uniqueToken();
            String keyword = token + "折叠屏";
            Product exact = createProduct(keyword + "手机", 5, "999.00");
            // 干扰项与关键词共享 ngram token，但不含完整子串
            createProduct(token + "普通屏幕手机", 5, "888.00");
            createProduct(keyword + "保护壳", 5, "777.00");

            ProductQueryDTO q = query(x -> x.setKeyword(keyword));
            PageResult<ProductCardVO> fulltext = fulltextService.search(q);

            assertThat(fulltext.getList()).extracting(ProductCardVO::getId).contains(exact.getId());
            assertEnginesAgree(q);
        }

        @Test
        @DisplayName("无关键词时三种引擎都退化为普通列表（不报错、结果一致）")
        void emptyKeywordFallsBackToPlainList() {
            ProductQueryDTO q = query(x -> x.setKeyword(null));
            q.setCat(PARENT_KEY);
            q.setPageSize(50);

            Map<String, PageResult<ProductCardVO>> results = runAll(q);
            long likeTotal = results.get("like").getTotal();
            for (Map.Entry<String, PageResult<ProductCardVO>> e : results.entrySet()) {
                assertThat(e.getValue().getTotal())
                        .as("引擎 %s 在无关键词时的 total", e.getKey())
                        .isEqualTo(likeTotal);
            }
        }

        @Test
        @DisplayName("引擎标识互不重复，便于日志与指标区分")
        void engineNamesAreDistinct() {
            assertThat(engines.values().stream()
                            .map(ProductSearchService::engineName)
                            .collect(Collectors.toSet()))
                    .hasSize(3)
                    .containsExactlyInAnyOrder("mysql-like", "mysql-fulltext", "elasticsearch");
        }
    }

    /* ============================ 断言与夹具 ============================ */

    /** 跑三种引擎，返回 引擎名 → 结果。 */
    private Map<String, PageResult<ProductCardVO>> runAll(ProductQueryDTO query) {
        return engines.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().search(query)));
    }

    /**
     * 核心断言：三种引擎的 total 与命中 id 集合必须完全一致。
     *
     * <p>用 id 集合（而非列表）比较：三者排序能力不同，顺序本就可以不同，
     * 但「命中哪些商品」是硬契约。</p>
     */
    private void assertEnginesAgree(ProductQueryDTO query) {
        Map<String, PageResult<ProductCardVO>> results = runAll(query);

        PageResult<ProductCardVO> baseline = results.get("like");
        long expectedTotal = baseline.getTotal();
        var expectedIds = idsOf(baseline);

        for (Map.Entry<String, PageResult<ProductCardVO>> e : results.entrySet()) {
            String engine = e.getKey();
            PageResult<ProductCardVO> actual = e.getValue();
            assertThat(actual.getTotal())
                    .as("引擎 %s 的 total 应与 mysql-like 一致（%d）", engine, expectedTotal)
                    .isEqualTo(expectedTotal);
            assertThat(idsOf(actual))
                    .as("引擎 %s 的命中 id 集合应与 mysql-like 一致", engine)
                    .containsExactlyInAnyOrderElementsOf(expectedIds);
        }
    }

    private java.util.Set<Long> idsOf(PageResult<ProductCardVO> page) {
        return page.getList().stream().map(ProductCardVO::getId).collect(Collectors.toSet());
    }

    private ProductQueryDTO query(java.util.function.Consumer<ProductQueryDTO> customizer) {
        ProductQueryDTO q = new ProductQueryDTO();
        q.setPage(1);
        q.setPageSize(50);
        customizer.accept(q);
        return q;
    }

    /** 每个用例用独立 token，保证各用例的商品互不干扰（共享同一个测试库）。 */
    private String uniqueToken() {
        return "CT" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 批量造商品：价格 100/200/300… 递增，销量与价格反向，便于验证排序。 */
    private List<Product> createProducts(String token, int count) {
        List<Product> products = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String title = token + " 契约测试商品" + i;
            Product p = createProduct(title, 5, String.valueOf((i + 1) * 100));
            p.setSales((count - i) * 10);
            productMapper.updateById(p);
            // 销量改动同样要同步到索引，否则 sales 排序的契约断言会失败
            indexDocuments(List.of(toEsDocument(p)));
            products.add(p);
        }
        return products;
    }

    private Product createProduct(String title, int stock, String price) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey(CATEGORY_KEY);
        product.setParentKey(PARENT_KEY);
        product.setCover("https://img/test/contract.png");
        product.setSpec("标准版");
        product.setPrice(new BigDecimal(price));
        product.setOldPrice(new BigDecimal(price).add(new BigDecimal("10.00")));
        product.setCost(new BigDecimal("1.00"));
        product.setStock(stock);
        product.setSafeStock(1);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("契约测试分类");
        product.setBrandName(BRAND);
        product.setTags("测试");
        product.setIsHot(0);
        product.setIsNew(1);
        productMapper.insert(product);
        indexDocuments(List.of(toEsDocument(product)));
        return product;
    }

    /**
     * MySQL 实体 → ES 文档。
     *
     * <p>字段名必须与 {@code ElasticProductSearchService} 里的 DSL 完全一致
     * （status / categoryKey / parentKey / brandName / price / sales / isHot / isNew），
     * 对不上就会「MySQL 有数据但 ES 搜不到」，契约测试会立刻失败。</p>
     */
    private static Map<String, Object> toEsDocument(Product p) {
        Map<String, Object> doc = new java.util.HashMap<>();
        doc.put("id", p.getId());
        doc.put("shopId", p.getShopId());
        doc.put("title", p.getTitle());
        doc.put("brandName", p.getBrandName());
        doc.put("categoryKey", p.getCategoryKey());
        doc.put("parentKey", p.getParentKey());
        doc.put("categoryName", p.getCategoryName());
        doc.put("price", p.getPrice() == null ? 0.0 : p.getPrice().doubleValue());
        doc.put("sales", p.getSales() == null ? 0 : p.getSales());
        doc.put("rating", p.getRating() == null ? 5.0 : p.getRating().doubleValue());
        doc.put("isHot", p.getIsHot() == null ? 0 : p.getIsHot());
        doc.put("isNew", p.getIsNew() == null ? 0 : p.getIsNew());
        doc.put("status", p.getStatus() == null ? 0 : p.getStatus());
        return doc;
    }
}
