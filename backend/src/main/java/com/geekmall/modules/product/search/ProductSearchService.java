package com.geekmall.modules.product.search;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.vo.ProductCardVO;

/**
 * 商品搜索的引擎抽象。
 *
 * <p><b>为什么要抽这一层</b>（与 {@code SeckillOrderQueue} 同一思路）：</p>
 * <ol>
 *   <li><b>可切换</b>：MySQL LIKE / MySQL FULLTEXT / Elasticsearch 三种实现并存，
 *       由 {@code mall.search.engine} 决定启用哪个，业务代码不动；</li>
 *   <li><b>可对比</b>：同一批关键词、同一套统计口径下做 A/B，才能回答
 *       「引入 ES 到底带来多少收益」而不是凭感觉；</li>
 *   <li><b>可降级</b>：ES 不可用时回落到数据库实现，搜索变慢但不中断。</li>
 * </ol>
 *
 * <p><b>契约约定</b>：所有实现必须返回<b>同样的输入 → 同样的输出</b>，
 * 尤其是 {@code total} 的口径要一致（都是「满足条件的总行数」）。
 * 否则前端分页会错乱，A/B 对比也失去意义。</p>
 */
public interface ProductSearchService {

    /**
     * 按查询条件返回商品卡片分页。
     *
     * <p>入参沿用现有的 {@link ProductQueryDTO}，出参沿用现有 {@link ProductCardVO}，
     * 保证前端契约零变化。</p>
     */
    PageResult<ProductCardVO> search(ProductQueryDTO query);

    /** 引擎标识，用于日志、指标与健康检查。 */
    String engineName();
}
