package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.IdsDTO;
import com.geekmall.modules.merchant.dto.MerchantReviewQueryDTO;
import com.geekmall.modules.merchant.dto.ReviewReplyDTO;
import com.geekmall.modules.merchant.service.MerchantReviewService;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商家端评价管理接口，对应前端 ReviewView。
 */
@Tag(name = "17-商家评价", description = "评价列表 / DSR 汇总 / 回复 / 忽略")
@RestController
@RequestMapping("/api/v1/merchant/review")
@RequiredArgsConstructor
public class MerchantReviewController {

    private final MerchantReviewService merchantReviewService;

    @Operation(summary = "评价分页列表")
    @GetMapping("/page")
    public Result<MerchantPageVO<MerchantReviewVO>> page(@Valid @ModelAttribute MerchantReviewQueryDTO query) {
        return Result.ok(merchantReviewService.page(query));
    }

    @Operation(summary = "店铺 DSR 与评价标签")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(merchantReviewService.summary());
    }

    @Operation(summary = "回复评价")
    @PostMapping("/reply")
    public Result<Void> reply(@Valid @RequestBody ReviewReplyDTO dto) {
        merchantReviewService.reply(dto);
        return Result.ok();
    }

    @Operation(summary = "忽略评价")
    @PostMapping("/ignore")
    public Result<Integer> ignore(@Valid @RequestBody IdsDTO dto) {
        return Result.ok(merchantReviewService.ignore(dto.getIds()));
    }
}
