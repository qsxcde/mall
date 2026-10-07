package com.geekmall.modules.review.controller;

import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.Result;
import com.geekmall.modules.review.dto.SubmitReviewDTO;
import com.geekmall.modules.review.service.ReviewService;
import com.geekmall.modules.review.vo.ReviewVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评价接口，对应前端 ReviewView / MyReviewsView / ProductView 的评价 Tab。
 */
@Tag(name = "13-评价", description = "发表评价 / 我的评价 / 商品评价")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "发表评价", description = "提交后订单自动流转为「已完成」")
    @PostMapping("/reviews")
    public Result<Integer> submit(@Validated @RequestBody SubmitReviewDTO dto) {
        return Result.ok(reviewService.submit(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "我的评价")
    @GetMapping("/user/reviews")
    public Result<List<ReviewVO>> myReviews() {
        return Result.ok(reviewService.myReviews(SecurityUtils.getUserId()));
    }

    @Operation(summary = "删除评价")
    @DeleteMapping("/user/reviews/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        reviewService.delete(SecurityUtils.getUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "商品评价列表", description = "白名单接口，匿名可浏览")
    @GetMapping("/products/{productId}/reviews")
    public Result<PageResult<ReviewVO>> productReviews(@PathVariable Long productId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(reviewService.productReviews(productId, page, pageSize));
    }
}
