package com.geekmall.modules.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.modules.review.entity.Review;
import com.geekmall.modules.review.mapper.ReviewMapper;
import com.geekmall.modules.review.service.MerchantReviewQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 商家端评价只读查询实现（所有查询强制 {@code shop_id} 隔离）。
 */
@Service
@RequiredArgsConstructor
public class MerchantReviewQueryServiceImpl implements MerchantReviewQueryService {

    private final ReviewMapper reviewMapper;

    @Override
    public IPage<Review> pageByShop(Long shopId, LambdaQueryWrapper<Review> wrapper, long page, long size) {
        return reviewMapper.selectPage(new Page<>(page, size), wrap(shopId, wrapper));
    }

    @Override
    public List<Review> listByShop(Long shopId, LambdaQueryWrapper<Review> wrapper) {
        return reviewMapper.selectList(wrap(shopId, wrapper));
    }

    @Override
    public Review findByShopAndId(Long shopId, Long id) {
        return reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                .eq(Review::getShopId, shopId)
                .eq(Review::getId, id)
                .last("limit 1"));
    }

    @Override
    public int updateMerchantFields(Review review) {
        return reviewMapper.updateById(review);
    }

    private static LambdaQueryWrapper<Review> wrap(Long shopId, LambdaQueryWrapper<Review> wrapper) {
        LambdaQueryWrapper<Review> target = wrapper == null ? new LambdaQueryWrapper<>() : wrapper;
        return target.eq(Review::getShopId, shopId);
    }
}
