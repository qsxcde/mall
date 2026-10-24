package com.geekmall.modules.product.search;

import com.geekmall.modules.product.entity.Product;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品 → ES 文档的映射（索引写入的唯一出处）。
 *
 * <h3>字段清单必须与索引 mapping 严格一致</h3>
 * <p>字段名对不上的后果不是报错，而是<b>静默查不到</b>：{@code term} 查询打在不存在的字段上
 * 只会返回空集，测试若只看「搜到了几条」很容易漏判。因此这里用显式的 {@code Map}
 * 而不是序列化整个实体 —— 实体字段与索引字段本来就是两个集合，混用迟早出事。</p>
 *
 * <h3>三条硬约束</h3>
 * <ol>
 *   <li><b>绝不写入 {@code cost}</b>：成本价是商家侧敏感字段，一旦进索引，
 *       将来任何一次「把 _source 原样返回」的改动都会把它泄露给买家；</li>
 *   <li><b>只索引召回/排序用得上的字段</b>：库存、浏览量、标签、封面都不进索引，
 *       它们要么强一致（必须回表），要么检索用不到；</li>
 *   <li><b>不放宽「只索引上架商品」</b>：{@code status != 1} 或已软删的商品由调用方
 *       从索引里删掉，而不是写一条 {@code status=0} 的文档 —— 索引里不该存在买不到的商品。</li>
 * </ol>
 *
 * <p>与 {@code loadtest/scripts/es_sync_products.py} 的全量导入共用同一份字段口径：
 * 改这里必须同步改脚本与索引 mapping，{@code ProductIndexDocumentTest} 会锁住字段集合。</p>
 */
final class ProductIndexDocument {

    /** 与索引 mapping 中 {@code releaseTime} 的 {@code format} 第一项保持一致。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ProductIndexDocument() {}

    /**
     * 构建待写入的文档。
     *
     * <p>入参必须是<b>已确认可索引</b>的商品（上架且未删除），
     * 「能不能索引」的判断留给调用方 —— 这里只做字段映射，不做业务决策。</p>
     */
    static Map<String, Object> of(Product product) {
        Map<String, Object> doc = new LinkedHashMap<>(16);
        doc.put("id", product.getId());
        doc.put("shopId", product.getShopId());
        // 字符串字段为空时不写，而不是写 null：ES 里「字段缺失」与「值为 null」对 term/range
        // 的语义一致，但显式 null 会让 _source 多出噪音，也容易在「原样返回 _source」的
        // 改动里变成前端看到的 "null" 字符串
        putIfPresent(doc, "title", product.getTitle());
        putIfPresent(doc, "brandName", product.getBrandName());
        putIfPresent(doc, "categoryKey", product.getCategoryKey());
        putIfPresent(doc, "parentKey", product.getParentKey());
        putIfPresent(doc, "categoryName", product.getCategoryName());
        // scaled_float(scaling_factor=100)：价格按「分」存整，避免浮点累计误差
        doc.put("price", product.getPrice() == null ? 0.0 : product.getPrice().doubleValue());
        doc.put("sales", product.getSales() == null ? 0 : product.getSales());
        doc.put(
                "rating",
                product.getRating() == null ? 0.0 : product.getRating().doubleValue());
        doc.put("isHot", product.getIsHot() == null ? 0 : product.getIsHot());
        doc.put("isNew", product.getIsNew() == null ? 0 : product.getIsNew());
        doc.put("status", product.getStatus() == null ? 0 : product.getStatus());
        LocalDateTime releaseTime = product.getReleaseTime();
        if (releaseTime != null) {
            // 显式格式化：交给 Jackson 猜的话会变成数组/ISO 串，与 mapping 的 format 对不上
            doc.put("releaseTime", releaseTime.format(DATE_FORMAT));
        }
        return doc;
    }

    private static void putIfPresent(Map<String, Object> doc, String field, String value) {
        if (value != null && !value.isBlank()) {
            doc.put(field, value);
        }
    }

    /** 索引该商品的可索引条件：上架且未软删。 */
    static boolean indexable(Product product) {
        return product != null
                && product.getStatus() != null
                && product.getStatus() == 1
                && (product.getDeleted() == null || product.getDeleted() == 0);
    }
}
