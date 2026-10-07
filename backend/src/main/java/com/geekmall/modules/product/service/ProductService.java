package com.geekmall.modules.product.service;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.product.dto.ProductQueryDTO;
import com.geekmall.modules.product.vo.CategoryVO;
import com.geekmall.modules.product.vo.HomeFloorVO;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.product.vo.ProductDetailVO;

import java.util.List;

/**
 * 商品与分类查询服务。
 */
public interface ProductService {

    /** 分类树（顶级分类 + 子分类）。 */
    List<CategoryVO> categoryTree();

    /** 商品分页查询：分类 / 关键词 / 品牌 / 价格区间 / 排序。 */
    PageResult<ProductCardVO> page(ProductQueryDTO query);

    /** 商品详情。 */
    ProductDetailVO detail(Long id);

    /** 看了又看：同分类热销推荐。 */
    List<ProductCardVO> recommend(Long id, int limit);

    /** 首页楼层聚合。 */
    HomeFloorVO homeFloors();

    /** 在售商品数（供内容域统计展示）。 */
    long countOnShelf();

    /** 按 ID 批量取商品卡片，保持传入顺序（供收藏 / 浏览足迹展示）。 */
    List<ProductCardVO> cardsByIds(java.util.Collection<Long> productIds);
}
