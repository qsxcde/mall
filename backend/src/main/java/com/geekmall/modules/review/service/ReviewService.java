package com.geekmall.modules.review.service;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.review.dto.SubmitReviewDTO;
import com.geekmall.modules.review.vo.ReviewVO;

import java.util.List;

/**
 * 评价服务。
 */
public interface ReviewService {

    /**
     * 提交评价：按订单内商品逐个生成评价，并把订单流转到「已完成」。
     *
     * @return 生成的评价条数
     */
    int submit(Long userId, SubmitReviewDTO dto);

    /** 我的评价。 */
    List<ReviewVO> myReviews(Long userId);

    /** 删除评价（逻辑删除）。 */
    void delete(Long userId, Long reviewId);

    /** 商品评价分页（匿名可访问）。 */
    PageResult<ReviewVO> productReviews(Long productId, int page, int pageSize);
}
