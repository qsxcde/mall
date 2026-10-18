package com.geekmall.modules.product.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.entity.Product;
import java.util.Arrays;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 商品查询条件构建器（数据库方案共用）。
 *
 * <p>把「哪些条件、怎么组合」集中在这一处，避免 LIKE / FULLTEXT / ES 三种实现
 * 各写一遍导致过滤器漏掉或语义漂移 —— 尤其在 A/B 对比时，
 * <b>条件不一致会让对比结果失去意义</b>。</p>
 *
 * <p>关键词的处理方式由调用方决定（LIKE 子串 vs FULLTEXT 匹配），
 * 其余过滤条件（分类、品牌、价格、热销、新品、上架状态）在此统一。</p>
 */
final class ProductQuerySupport {

    /** 上架状态。 */
    static final int ON_SHELF = 1;

    private ProductQuerySupport() {}

    /**
     * 构建除关键词之外的所有过滤条件。
     *
     * <p>调用方在拿到 wrapper 后自行追加关键词条件，这样 LIKE 与 FULLTEXT
     * 可以共享同一套分类 / 品牌 / 价格 / 状态过滤。</p>
     */
    static LambdaQueryWrapper<Product> baseWrapper(ProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getStatus, ON_SHELF);

        // 子分类优先，其次顶级分类（与原有语义完全一致）
        if (StringUtils.hasText(query.getSub())) {
            wrapper.eq(Product::getCategoryKey, query.getSub());
        } else if (StringUtils.hasText(query.getCat())) {
            wrapper.eq(Product::getParentKey, query.getCat());
        }

        if (StringUtils.hasText(query.getBrand())) {
            List<String> brands = Arrays.stream(query.getBrand().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!brands.isEmpty()) {
                wrapper.in(Product::getBrandName, brands);
            }
        }
        if (query.getPriceMin() != null) {
            wrapper.ge(Product::getPrice, query.getPriceMin());
        }
        if (query.getPriceMax() != null) {
            wrapper.le(Product::getPrice, query.getPriceMax());
        }
        if (Boolean.TRUE.equals(query.getHot())) {
            wrapper.eq(Product::getIsHot, 1);
        }
        if (Boolean.TRUE.equals(query.getIsNew())) {
            wrapper.eq(Product::getIsNew, 1);
        }
        return wrapper;
    }

    /** 排序规则（LIKE / FULLTEXT 共用，与原有 applySort 完全一致）。 */
    static void applySort(LambdaQueryWrapper<Product> wrapper, String sort) {
        String value = sort == null ? "" : sort;
        switch (value) {
            case "sales" -> wrapper.orderByDesc(Product::getSales);
            case "price_asc" -> wrapper.orderByAsc(Product::getPrice);
            case "price_desc" -> wrapper.orderByDesc(Product::getPrice);
            case "rating" -> wrapper.orderByDesc(Product::getRating);
            case "new" -> wrapper.orderByDesc(Product::getId);
            default -> wrapper.orderByDesc(Product::getIsHot).orderByDesc(Product::getSales);
        }
    }
}
