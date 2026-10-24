package com.geekmall.modules.product.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.modules.product.entity.Product;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 商品索引文档映射的单元测试。
 *
 * <p>为什么这些断言值得单独写：索引字段写错的症状是<b>静默查不到</b>，
 * 而不是报错。字段名/类型/成本价泄露这三类问题只有靠显式断言才能守住。</p>
 */
class ProductIndexDocumentTest {

    @Test
    @DisplayName("文档字段集合与索引 mapping 完全一致（多一个少一个都要改这里）")
    void documentFieldsMatchIndexMapping() {
        Map<String, Object> doc = ProductIndexDocument.of(fullProduct());

        assertThat(doc.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "shopId",
                        "title",
                        "brandName",
                        "categoryKey",
                        "parentKey",
                        "categoryName",
                        "price",
                        "sales",
                        "rating",
                        "isHot",
                        "isNew",
                        "status",
                        "releaseTime");
    }

    @Test
    @DisplayName("成本价绝不进索引 —— 商家敏感字段不能通过搜索接口泄露")
    void costIsNeverIndexed() {
        Product product = fullProduct();
        product.setCost(new BigDecimal("999.99"));

        Map<String, Object> doc = ProductIndexDocument.of(product);

        assertThat(doc).doesNotContainKey("cost");
        assertThat(doc.values()).noneMatch(v -> "999.99".equals(String.valueOf(v)));
    }

    @Test
    @DisplayName("库存/浏览量/标签/封面不进索引：要么强一致必须回表，要么检索用不到")
    void strongConsistencyFieldsAreNotIndexed() {
        Map<String, Object> doc = ProductIndexDocument.of(fullProduct());

        assertThat(doc).doesNotContainKeys("stock", "views", "tags", "cover", "oldPrice", "deleted");
    }

    @Test
    @DisplayName("releaseTime 按 mapping 的 format 输出 yyyy-MM-dd HH:mm:ss（不是数组、不是 ISO 串）")
    void releaseTimeUsesMappingDateFormat() {
        Product product = fullProduct();
        product.setReleaseTime(LocalDateTime.of(2026, 9, 12, 10, 30, 5));

        assertThat(ProductIndexDocument.of(product).get("releaseTime")).isEqualTo("2026-09-12 10:30:05");
    }

    @Test
    @DisplayName("空值字段不产生 null 值：null 会污染 range 过滤与排序")
    void nullFieldsFallBackToZeroOrDefaults() {
        Product product = new Product();
        product.setId(1L);
        product.setShopId(1L);
        product.setStatus(1);

        Map<String, Object> doc = ProductIndexDocument.of(product);

        assertThat(doc.get("price")).isEqualTo(0.0);
        assertThat(doc.get("sales")).isEqualTo(0);
        assertThat(doc.get("rating")).isEqualTo(0.0);
        assertThat(doc.get("isHot")).isEqualTo(0);
        assertThat(doc.get("isNew")).isEqualTo(0);
        // 没有发布时间就不要这个字段，避免 mapping 的 date 解析 null 报错
        assertThat(doc).doesNotContainKey("releaseTime");
        assertThat(doc.values()).doesNotContainNull();
    }

    @Test
    @DisplayName("价格按 double 输出：mapping 是 scaled_float(100)，按分存整避免浮点累计误差")
    void priceIsScaledFloatCompatible() {
        Product product = fullProduct();
        product.setPrice(new BigDecimal("1999.99"));

        assertThat(ProductIndexDocument.of(product).get("price")).isEqualTo(1999.99);
    }

    @Test
    @DisplayName("只有「上架 + 未软删」可索引；下架或已删必须从索引移除")
    void onlyOnShelfAndNotDeletedIsIndexable() {
        Product onShelf = fullProduct();
        onShelf.setStatus(1);
        onShelf.setDeleted(0);
        assertThat(ProductIndexDocument.indexable(onShelf)).isTrue();

        Product offShelf = fullProduct();
        offShelf.setStatus(0);
        assertThat(ProductIndexDocument.indexable(offShelf)).isFalse();

        Product deleted = fullProduct();
        deleted.setDeleted(1);
        assertThat(ProductIndexDocument.indexable(deleted)).isFalse();

        Product statusUnknown = fullProduct();
        statusUnknown.setStatus(null);
        assertThat(ProductIndexDocument.indexable(statusUnknown)).isFalse();

        assertThat(ProductIndexDocument.indexable(null)).isFalse();
    }

    private static Product fullProduct() {
        Product product = new Product();
        product.setId(1001L);
        product.setShopId(1L);
        product.setTitle("极客手机 Pro 256G 钛金属");
        product.setBrandName("极客严选");
        product.setCategoryKey("phone");
        product.setParentKey("digital");
        product.setCategoryName("手机");
        product.setPrice(new BigDecimal("4999.00"));
        product.setSales(12);
        product.setRating(new BigDecimal("4.8"));
        product.setIsHot(1);
        product.setIsNew(1);
        product.setStatus(1);
        product.setDeleted(0);
        product.setStock(88);
        product.setViews(1024);
        product.setTags("热销,包邮");
        product.setCover("https://img/cover.png");
        product.setOldPrice(new BigDecimal("5299.00"));
        product.setCost(new BigDecimal("3800.00"));
        product.setReleaseTime(LocalDateTime.of(2026, 9, 12, 10, 30, 5));
        return product;
    }
}
