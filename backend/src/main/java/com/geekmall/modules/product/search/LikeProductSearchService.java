package com.geekmall.modules.product.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.result.PageResult;
import com.geekmall.modules.product.converter.ProductConverter;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.product.vo.ProductCardVO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 数据库 LIKE 实现（<b>默认</b>，也是原有行为）。
 *
 * <p>语义：关键词在「标题 / 品牌 / 分类名」三列上做 {@code LIKE '%kw%'} 子串匹配。</p>
 *
 * <h3>已知性能边界（实测 100 万行，见 docs/benchmark/商品搜索容量边界实测.md）</h3>
 * <ul>
 *   <li>高命中的词（如「华为」13 万条）约 1.0 s —— 可借 {@code ORDER BY sales LIMIT 8}
 *       沿 {@code idx_sales} 提前终止；</li>
 *   <li><b>低频词（如 iPhone 仅 2 条）约 5.2 s</b> —— 必须扫完整个索引才能确认凑不满，
 *       这是真正的最坏情况；</li>
 *   <li>并发 6 时达到 10.3 s，且各请求耗时相同即「在排队」，无横向扩展能力。</li>
 * </ul>
 * <p>保留此实现的价值：数据量小时它没有额外组件成本，且可作为其他引擎的降级兜底。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.search.engine", havingValue = "mysql-like", matchIfMissing = true)
public class LikeProductSearchService implements ProductSearchService {

    /**
     * 供 ES 实现做降级兜底用的<b>非 Bean 实例</b>。
     *
     * <p>本类在 {@code engine=elasticsearch} 时不会被注册成 Bean，但 ES 实现需要它作为
     * 熔断回退 —— 而回退逻辑本身只依赖 {@code ProductMapper}，不依赖 Spring 容器能力。
     * 因此由 ES 实现直接构造一个实例，既避免条件装配的循环，也让降级路径在任何
     * 引擎配置下都可用。</p>
     */
    public static LikeProductSearchService forFallback(ProductMapper mapper) {
        return new LikeProductSearchService(mapper);
    }

    private final ProductMapper productMapper;

    @Override
    public PageResult<ProductCardVO> search(ProductQueryDTO query) {
        LambdaQueryWrapper<Product> wrapper = ProductQuerySupport.baseWrapper(query);

        // 关键词：标题 / 品牌 / 分类名，OR 需要与前面的 AND 分组，避免条件泄漏
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(Product::getTitle, keyword)
                    .or()
                    .like(Product::getBrandName, keyword)
                    .or()
                    .like(Product::getCategoryName, keyword));
        }

        ProductQuerySupport.applySort(wrapper, query.getSort());
        Page<Product> page = new Page<>(query.getPage(), query.getPageSize());
        IPage<Product> result = productMapper.selectPage(page, wrapper);
        return PageResult.of(result, ProductConverter::toCard);
    }

    @Override
    public String engineName() {
        return "mysql-like";
    }

    /** 供降级场景复用：按 id 顺序回表组装（保持搜索结果的顺序）。 */
    static List<Product> loadInOrder(ProductMapper mapper, List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return List.of();
        }
        var byId = new java.util.HashMap<Long, Product>();
        for (Product p : mapper.selectOnShelfByIds(orderedIds)) {
            byId.put(p.getId(), p);
        }
        List<Product> ordered = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Product p = byId.get(id);
            if (p != null) {
                ordered.add(p);
            }
        }
        return ordered;
    }
}
