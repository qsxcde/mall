package com.geekmall.modules.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.geekmall.modules.review.entity.Review;
import java.util.List;

/**
 * 面向<b>商家端</b>的评价只读查询服务。
 *
 * <p>与 {@link MerchantOrderQueryService} 同一设计动机：商家域原本直接
 * {@code import ReviewMapper} 自己拼条件，导致 {@code pms_review} 的软删条件、
 * 店铺隔离条件（{@code shop_id = ?}）散落在商家域各处。收敛到这里后，
 * 隔离条件由被依赖方保证，调用方无法漏写。</p>
 */
public interface MerchantReviewQueryService {

    /** 按店铺 + 条件分页。 */
    IPage<Review> pageByShop(Long shopId, LambdaQueryWrapper<Review> wrapper, long page, long size);

    /** 按店铺 + 条件查询列表。 */
    List<Review> listByShop(Long shopId, LambdaQueryWrapper<Review> wrapper);

    /** 按店铺 + 评价 ID 查询，不存在返回 null。 */
    Review findByShopAndId(Long shopId, Long id);

    /**
     * 更新评价（仅商家侧可维护的字段：回复内容、忽略标记）。
     *
     * <p>不允许改评分/内容 —— 那是买家提交的原始评价。</p>
     */
    int updateMerchantFields(Review review);
}
