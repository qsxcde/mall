package com.geekmall.modules.product.converter;

import com.geekmall.modules.product.entity.Category;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.vo.CategoryVO;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.product.vo.ProductDetailVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品转换器单元测试。
 *
 * <p>覆盖实体字段 → 前端字段的改名（brandName→brand、categoryName→cat）与
 * 逗号标签的拆包，这两处是最容易在重构中被改错的地方。</p>
 */
class ProductConverterTest {

    private static Product product() {
        Product product = new Product();
        product.setId(11L);
        product.setTitle("极客手机 Pro");
        product.setCover("https://img/cover.png");
        product.setPrice(new BigDecimal("5999.00"));
        product.setOldPrice(new BigDecimal("6499.00"));
        product.setSpec("256G 钛金属");
        product.setSales(320);
        product.setRating(new BigDecimal("4.9"));
        product.setBrandName("极客");
        product.setCategoryName("手机数码");
        product.setTags("热销, 包邮 ,,新品 ");
        product.setCategoryId(2L);
        product.setCategoryKey("phone");
        product.setParentKey("digital");
        product.setBrandId(3L);
        product.setIsHot(1);
        product.setIsNew(0);
        product.setStock(88);
        return product;
    }

    @Nested
    @DisplayName("商品卡片")
    class Card {

        @Test
        @DisplayName("null 入参返回 null，不构造空对象")
        void shouldReturnNull() {
            assertThat(ProductConverter.toCard(null)).isNull();
        }

        @Test
        @DisplayName("字段改名与标签拆包结果符合前端契约")
        void shouldMapFields() {
            ProductCardVO vo = ProductConverter.toCard(product());

            assertThat(vo.getId()).isEqualTo(11L);
            assertThat(vo.getTitle()).isEqualTo("极客手机 Pro");
            assertThat(vo.getCover()).isEqualTo("https://img/cover.png");
            assertThat(vo.getPrice()).isEqualByComparingTo("5999.00");
            assertThat(vo.getOldPrice()).isEqualByComparingTo("6499.00");
            assertThat(vo.getSpec()).isEqualTo("256G 钛金属");
            assertThat(vo.getSales()).isEqualTo(320);
            assertThat(vo.getRating()).isEqualByComparingTo("4.9");
            // 实体是 brandName / categoryName，前端要 brand / cat
            assertThat(vo.getBrand()).isEqualTo("极客");
            assertThat(vo.getCat()).isEqualTo("手机数码");
            assertThat(vo.getTags()).containsExactly("热销", "包邮", "新品");
        }
    }

    @Nested
    @DisplayName("商品详情")
    class Detail {

        @Test
        @DisplayName("null 入参返回 null")
        void shouldReturnNull() {
            assertThat(ProductConverter.toDetail(null)).isNull();
        }

        @Test
        @DisplayName("继承卡片字段并补齐详情字段")
        void shouldMapDetailFields() {
            ProductDetailVO vo = ProductConverter.toDetail(product());

            assertThat(vo.getCategoryId()).isEqualTo(2L);
            assertThat(vo.getCategoryKey()).isEqualTo("phone");
            assertThat(vo.getParentKey()).isEqualTo("digital");
            assertThat(vo.getBrandId()).isEqualTo(3L);
            assertThat(vo.getDescription()).isEqualTo("极客手机 Pro");
            assertThat(vo.getStock()).isEqualTo(88);
            assertThat(vo.getDisplayPrice()).isEqualByComparingTo("5999.00");
            // isHot=1 → hot=true；isNew=0 → isNew=false
            assertThat(vo.getHot()).isTrue();
            assertThat(vo.getIsNew()).isFalse();
        }

        @Test
        @DisplayName("有封面时图集含封面；无封面时图集为空而不是含 null 的列表")
        void shouldBuildImages() {
            assertThat(ProductConverter.toDetail(product()).getImages())
                    .containsExactly("https://img/cover.png");

            Product noCover = product();
            noCover.setCover(null);
            assertThat(ProductConverter.toDetail(noCover).getImages()).isEmpty();
        }

        @Test
        @DisplayName("标记位为 null 时按「否」处理，避免拆箱 NPE")
        void shouldHandleNullFlags() {
            Product product = product();
            product.setIsHot(null);
            product.setIsNew(null);

            ProductDetailVO vo = ProductConverter.toDetail(product);

            assertThat(vo.getHot()).isFalse();
            assertThat(vo.getIsNew()).isFalse();
        }
    }

    @Nested
    @DisplayName("分类")
    class CategoryMapping {

        @Test
        @DisplayName("null 入参返回 null")
        void shouldReturnNull() {
            assertThat(ProductConverter.toCategoryVO(null)).isNull();
        }

        @Test
        @DisplayName("分类字段完整映射")
        void shouldMapCategory() {
            Category category = new Category();
            category.setId(5L);
            category.setCategoryKey("laptop");
            category.setName("笔记本电脑");
            category.setDescription("轻办公与高性能");
            category.setIcon("laptop.svg");

            CategoryVO vo = ProductConverter.toCategoryVO(category);

            assertThat(vo.getId()).isEqualTo(5L);
            assertThat(vo.getCategoryKey()).isEqualTo("laptop");
            assertThat(vo.getName()).isEqualTo("笔记本电脑");
            assertThat(vo.getDescription()).isEqualTo("轻办公与高性能");
            assertThat(vo.getIcon()).isEqualTo("laptop.svg");
        }
    }

    @Nested
    @DisplayName("标签拆包")
    class SplitTags {

        @Test
        @DisplayName("null 与空白返回空列表")
        void shouldReturnEmpty() {
            assertThat(ProductConverter.splitTags(null)).isEmpty();
            assertThat(ProductConverter.splitTags("")).isEmpty();
            assertThat(ProductConverter.splitTags("   ")).isEmpty();
        }

        @Test
        @DisplayName("去除空白项与首尾空格，过滤空串")
        void shouldTrimAndFilter() {
            assertThat(ProductConverter.splitTags(" 热销 , 包邮 ,, 新品 "))
                    .containsExactly("热销", "包邮", "新品");
        }

        @Test
        @DisplayName("单个标签也能正确解析")
        void shouldHandleSingleTag() {
            assertThat(ProductConverter.splitTags("热销")).containsExactly("热销");
        }

        @Test
        @DisplayName("标签为空时卡片返回空列表而不是 null，前端可直接遍历")
        void cardTagsShouldNeverBeNull() {
            Product product = product();
            product.setTags(null);
            assertThat(ProductConverter.toCard(product).getTags()).isEqualTo(List.of());
        }
    }
}
