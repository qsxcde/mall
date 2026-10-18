package com.geekmall.modules.product.search;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.product.converter.ProductConverter;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.vo.ProductCardVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * MySQL FULLTEXT（ngram）实现。
 *
 * <p>通过启用 {@code mall.search.engine=mysql-fulltext} 切换。
 * 依赖迁移脚本建立的全文索引：</p>
 * <pre>
 * ALTER TABLE pms_product
 *   ADD FULLTEXT INDEX ft_search (title, brand_name, category_name) WITH PARSER ngram;
 * </pre>
 *
 * <h3>为什么必须用「短语模式」而不是普通 BOOLEAN 模式</h3>
 * <p>ngram 分词器把中文切成<b>双字 token</b>（{@code ngram_token_size=2}）。
 * 普通 BOOLEAN 模式是「各 token 分别匹配（OR）」，搜「折叠屏」会被拆成
 * 「折叠」「叠屏」两个词，只要命中任一就算命中 —— <b>命中集会比 LIKE 大</b>，
 * 语义不等价。加双引号变成短语查询（要求 token 连续出现），才与
 * {@code LIKE '%折叠屏%'} 等价。</p>
 *
 * <p>实测验证（100 万行）：「折叠屏」三种口径命中数均为 106364，「华为」均为 138384，
 * 「MateBook」均为 20284 —— 语义等价。</p>
 *
 * <h3>性能特征（与 LIKE 恰好相反，这是最容易误判的地方）</h3>
 * <ul>
 *   <li><b>低频词 / 无匹配</b>：从 LIKE 的 5.2 s 降到<b>亚毫秒</b> —— 倒排索引直接给出空集；</li>
 *   <li><b>高频词</b>：反而比 LIKE 慢（1.0 s → 3.2 s）—— 命中 13 万条时，
 *       倒排索引要取出全部命中 id 再回表排序，比 LIKE 沿 {@code idx_sales} 提前终止更重。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.search.engine", havingValue = "mysql-fulltext")
public class FulltextProductSearchService implements ProductSearchService {

    private final ProductMapper productMapper;

    @Override
    public PageResult<ProductCardVO> search(ProductQueryDTO query) {
        String keyword =
                StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;

        // 无关键词时退回普通列表查询（全文索引帮不上忙，也不该走 MATCH）
        if (keyword == null) {
            return fallbackToPlainList(query);
        }

        long page = query.getPage() == null ? 1 : Math.max(1, query.getPage());
        long pageSize = query.getPageSize() == null ? 8 : Math.max(1, query.getPageSize());
        long offset = (page - 1) * pageSize;

        // 短语模式：必须整体加双引号，否则 ngram 会拆成两个词做 OR（见类注释）
        String phrase = "\"" + keyword.replace("\"", "") + "\"";

        long total = productMapper.countFulltext(
                phrase,
                query.getSub(),
                query.getCat(),
                brandsOf(query),
                query.getPriceMin(),
                query.getPriceMax(),
                boolOf(query.getHot()),
                boolOf(query.getIsNew()));

        if (total == 0) {
            return PageResult.empty(page, pageSize);
        }

        List<Long> ids = productMapper.searchFulltextIds(
                phrase,
                query.getSub(),
                query.getCat(),
                brandsOf(query),
                query.getPriceMin(),
                query.getPriceMax(),
                boolOf(query.getHot()),
                boolOf(query.getIsNew()),
                sortOf(query.getSort()),
                pageSize,
                offset);

        // 回表：价格/库存/上下架以 MySQL 为准，索引只负责"哪些 id 命中"
        List<Product> ordered = LikeProductSearchService.loadInOrder(productMapper, ids);
        return new PageResult<>(ordered.stream().map(ProductConverter::toCard).toList(), total, page, pageSize);
    }

    @Override
    public String engineName() {
        return "mysql-fulltext";
    }

    private PageResult<ProductCardVO> fallbackToPlainList(ProductQueryDTO query) {
        // 复用 LIKE 实现的列表逻辑（它本身不依赖关键词）
        var wrapper = ProductQuerySupport.baseWrapper(query);
        ProductQuerySupport.applySort(wrapper, query.getSort());
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Product>(
                query.getPage(), query.getPageSize());
        var result = productMapper.selectPage(page, wrapper);
        return PageResult.of(result, ProductConverter::toCard);
    }

    private static List<String> brandsOf(ProductQueryDTO query) {
        if (!StringUtils.hasText(query.getBrand())) {
            return null;
        }
        List<String> brands = java.util.Arrays.stream(query.getBrand().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return brands.isEmpty() ? null : brands;
    }

    private static Integer boolOf(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : null;
    }

    /** 与 LIKE 实现保持完全一致的排序口径。 */
    private static String sortOf(String sort) {
        String value = sort == null ? "" : sort;
        return switch (value) {
            case "sales", "price_asc", "price_desc", "rating", "new" -> value;
            default -> "default";
        };
    }
}
