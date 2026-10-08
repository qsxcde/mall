package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.MerchantOrderQueryDTO;
import com.geekmall.modules.merchant.dto.OrderBatchDTO;
import com.geekmall.modules.merchant.dto.OrderNoteDTO;
import com.geekmall.modules.merchant.service.MerchantOrderService;
import com.geekmall.modules.merchant.vo.MerchantOrderVO;
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
 * 商家端订单管理接口，对应前端 OrderListView。
 */
@Tag(name = "13-商家订单", description = "订单列表 / 详情 / 发货 / 关闭 / 备注")
@PreAuthorize("hasRole('MERCHANT')")
@RestController
@RequestMapping("/api/v1/merchant/order")
@RequiredArgsConstructor
public class MerchantOrderController {

    private final MerchantOrderService merchantOrderService;

    @Operation(summary = "订单分页列表")
    @GetMapping("/page")
    public Result<MerchantPageVO<MerchantOrderVO>> page(@Valid @ModelAttribute MerchantOrderQueryDTO query) {
        return Result.ok(merchantOrderService.page(query));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/detail")
    public Result<MerchantOrderVO> detail(@RequestParam String id) {
        return Result.ok(merchantOrderService.detail(id));
    }

    @Operation(summary = "发货")
    @PostMapping("/ship")
    public Result<Integer> ship(@Valid @RequestBody OrderBatchDTO dto) {
        return Result.ok(merchantOrderService.ship(dto));
    }

    @Operation(summary = "关闭订单")
    @PostMapping("/close")
    public Result<Integer> close(@Valid @RequestBody OrderBatchDTO dto) {
        return Result.ok(merchantOrderService.close(dto));
    }

    @Operation(summary = "保存商家备注")
    @PostMapping("/note")
    public Result<Void> note(@Valid @RequestBody OrderNoteDTO dto) {
        merchantOrderService.saveNote(dto);
        return Result.ok();
    }
}
