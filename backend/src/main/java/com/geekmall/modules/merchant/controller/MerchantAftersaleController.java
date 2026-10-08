package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.AftersaleResolveDTO;
import com.geekmall.modules.merchant.dto.MerchantAftersaleQueryDTO;
import com.geekmall.modules.merchant.service.MerchantAftersaleService;
import com.geekmall.modules.merchant.vo.MerchantAftersaleVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端售后管理接口，对应前端 AfterSaleView。
 */
@Tag(name = "15-商家售后", description = "售后列表 / 详情 / 处理")
@PreAuthorize("hasRole('MERCHANT')")
@RestController
@RequestMapping("/api/v1/merchant/aftersale")
@RequiredArgsConstructor
public class MerchantAftersaleController {

    private final MerchantAftersaleService merchantAftersaleService;

    @Operation(summary = "售后工单分页列表")
    @GetMapping("/page")
    public Result<MerchantPageVO<MerchantAftersaleVO>> page(@Valid @ModelAttribute MerchantAftersaleQueryDTO query) {
        return Result.ok(merchantAftersaleService.page(query));
    }

    @Operation(summary = "售后工单详情")
    @GetMapping("/detail")
    public Result<MerchantAftersaleVO> detail(@RequestParam String id) {
        return Result.ok(merchantAftersaleService.detail(id));
    }

    @Operation(summary = "处理售后工单")
    @PostMapping("/resolve")
    public Result<Integer> resolve(@Valid @RequestBody AftersaleResolveDTO dto) {
        return Result.ok(merchantAftersaleService.resolve(dto));
    }
}
