package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.MerchantProductQueryDTO;
import com.geekmall.modules.merchant.dto.ProductBatchDTO;
import com.geekmall.modules.merchant.dto.ProductSaveDTO;
import com.geekmall.modules.merchant.service.MerchantProductService;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商家端商品管理接口，对应前端 ProductListView。
 */
@Tag(name = "12-商家商品", description = "商品列表 / 保存 / 上下架 / 批量改价")
@PreAuthorize("hasRole('MERCHANT')")
@RestController
@RequestMapping("/api/v1/merchant/product")
@RequiredArgsConstructor
public class MerchantProductController {

    private final MerchantProductService merchantProductService;

    @Operation(summary = "商品分页列表")
    @GetMapping("/page")
    public Result<MerchantPageVO<MerchantProductVO>> page(@Valid @ModelAttribute MerchantProductQueryDTO query) {
        return Result.ok(merchantProductService.page(query));
    }

    @Operation(summary = "筛选下拉")
    @GetMapping("/filters")
    public Result<Map<String, Object>> filters() {
        return Result.ok(merchantProductService.filters());
    }

    @Operation(summary = "保存商品（新增或编辑）")
    @PostMapping("/save")
    public Result<Long> save(@Valid @RequestBody ProductSaveDTO dto) {
        return Result.ok(merchantProductService.save(dto));
    }

    @Operation(summary = "变更商品状态（上下架 / 回收站）")
    @PostMapping("/status")
    public Result<Integer> updateStatus(@Valid @RequestBody ProductBatchDTO dto) {
        return Result.ok(merchantProductService.updateStatus(dto));
    }

    @Operation(summary = "批量改价")
    @PostMapping("/batch-price")
    public Result<Integer> batchPrice(@Valid @RequestBody ProductBatchDTO dto) {
        return Result.ok(merchantProductService.batchUpdatePrice(dto));
    }
}
