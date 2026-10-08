package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.MerchantShipmentQueryDTO;
import com.geekmall.modules.merchant.dto.OrderBatchDTO;
import com.geekmall.modules.merchant.service.MerchantShipmentService;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantShipmentVO;
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
 * 商家端发货中心接口，对应前端 ShippingDeskView。
 */
@Tag(name = "14-商家发货", description = "发货单列表 / 打单 / 确认发货")
@PreAuthorize("hasRole('MERCHANT')")
@RestController
@RequestMapping("/api/v1/merchant/shipment")
@RequiredArgsConstructor
public class MerchantShipmentController {

    private final MerchantShipmentService merchantShipmentService;

    @Operation(summary = "发货单分页列表")
    @GetMapping("/page")
    public Result<MerchantPageVO<MerchantShipmentVO>> page(@Valid @ModelAttribute MerchantShipmentQueryDTO query) {
        return Result.ok(merchantShipmentService.page(query));
    }

    @Operation(summary = "快递公司 / 发货仓下拉")
    @GetMapping("/filters")
    public Result<Map<String, Object>> filters() {
        return Result.ok(merchantShipmentService.filters());
    }

    @Operation(summary = "打单（生成运单号）")
    @PostMapping("/print")
    public Result<Integer> print(@Valid @RequestBody OrderBatchDTO dto) {
        return Result.ok(merchantShipmentService.print(dto.getIds()));
    }

    @Operation(summary = "确认发货")
    @PostMapping("/deliver")
    public Result<Integer> deliver(@Valid @RequestBody OrderBatchDTO dto) {
        return Result.ok(merchantShipmentService.deliver(dto));
    }
}
