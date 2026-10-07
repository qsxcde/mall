package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.MerchantPromotionQueryDTO;
import com.geekmall.modules.merchant.service.MerchantPromotionService;
import com.geekmall.modules.merchant.vo.MerchantCouponVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantPromotionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商家端营销中心接口，对应前端 MarketingView。
 */
@Tag(name = "16-商家营销", description = "营销活动 / 渠道效果 / 店铺优惠券")
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class MerchantPromotionController {

    private final MerchantPromotionService merchantPromotionService;

    @Operation(summary = "营销活动分页列表")
    @GetMapping("/promotion/page")
    public Result<MerchantPageVO<MerchantPromotionVO>> page(@Valid @ModelAttribute MerchantPromotionQueryDTO query) {
        return Result.ok(merchantPromotionService.page(query));
    }

    @Operation(summary = "推广渠道效果")
    @GetMapping("/promotion/channels")
    public Result<List<Map<String, Object>>> channels() {
        return Result.ok(merchantPromotionService.channels());
    }

    @Operation(summary = "店铺优惠券列表")
    @GetMapping("/coupon/list")
    public Result<List<MerchantCouponVO>> coupons() {
        return Result.ok(merchantPromotionService.coupons());
    }
}
