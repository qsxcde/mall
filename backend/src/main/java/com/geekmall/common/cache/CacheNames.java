package com.geekmall.common.cache;

/**
 * 业务缓存名集中定义。
 *
 * <p>缓存名是「缓存治理」的分布维度：TTL、是否启用本地 L1、是否允许空值缓存
 * 都按名字在 {@link CacheProperties} 中声明。集中在此处定义可避免字符串散落，
 * 也让 {@code @Cacheable} / {@code @CacheEvict} 的改动能被一次性审查到。</p>
 */
public final class CacheNames {

    private CacheNames() {
    }

    /** 首页楼层聚合。 */
    public static final String HOME_FLOORS = "homeFloors";

    /** 分类树。变更频率最低，TTL 最长。 */
    public static final String CATEGORY_TREE = "categoryTree";

    /** 商品详情。价格 / 库存相对敏感，TTL 最短。 */
    public static final String PRODUCT_DETAIL = "productDetail";

    /** 「看了又看」推荐位。 */
    public static final String PRODUCT_RECOMMEND = "productRecommend";

    /** 全部缓存名，供预热 / 清理 / 断言使用。 */
    public static final String[] ALL = {
            HOME_FLOORS, CATEGORY_TREE, PRODUCT_DETAIL, PRODUCT_RECOMMEND
    };
}
