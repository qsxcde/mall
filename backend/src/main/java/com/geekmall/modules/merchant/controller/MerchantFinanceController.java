package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.MerchantFinanceQueryDTO;
import com.geekmall.modules.merchant.dto.WithdrawDTO;
import com.geekmall.modules.merchant.service.MerchantFinanceService;
import com.geekmall.modules.merchant.vo.MerchantFundFlowVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantSettlementVO;
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

import java.util.Map;

/**
 * 商家端财务结算接口，对应前端 FinanceSettleView。
 */
@Tag(name = "18-商家财务", description = "资金总览 / 结算单 / 资金流水 / 提现")
@PreAuthorize("hasRole('MERCHANT')")
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class MerchantFinanceController {

    private final MerchantFinanceService merchantFinanceService;

    @Operation(summary = "资金总览")
    @GetMapping("/fund/summary")
    public Result<Map<String, Object>> fundSummary() {
        return Result.ok(merchantFinanceService.fundSummary());
    }

    @Operation(summary = "结算单分页列表")
    @GetMapping("/settlement/page")
    public Result<MerchantPageVO<MerchantSettlementVO>> settlementPage(
            @Valid @ModelAttribute MerchantFinanceQueryDTO query) {
        return Result.ok(merchantFinanceService.settlementPage(query));
    }

    @Operation(summary = "结算单明细（抽样订单）")
    @GetMapping("/settlement/orders")
    public Result<Map<String, Object>> settleOrders(@RequestParam String id) {
        return Result.ok(merchantFinanceService.settleOrders(id));
    }

    @Operation(summary = "资金流水分页")
    @GetMapping("/fund/flow")
    public Result<MerchantPageVO<MerchantFundFlowVO>> fundFlow(
            @Valid @ModelAttribute MerchantFinanceQueryDTO query) {
        return Result.ok(merchantFinanceService.fundFlow(query));
    }

    @Operation(summary = "提现申请")
    @PostMapping("/fund/withdraw")
    public Result<Map<String, Object>> withdraw(@Valid @RequestBody WithdrawDTO dto) {
        return Result.ok(merchantFinanceService.withdraw(dto));
    }
}
