package com.geekmall.modules.payment.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.payment.dto.CreatePaymentDTO;
import com.geekmall.modules.payment.dto.PayCallbackDTO;
import com.geekmall.modules.payment.service.PaymentService;
import com.geekmall.modules.payment.vo.PaymentVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口，对应前端 PaymentView。
 */
@Tag(name = "09-支付", description = "创建支付 / 查询状态 / 模拟支付 / 渠道回调")
@RestController
@RequestMapping("/api/v1/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "创建支付单")
    @PostMapping("/create")
    public Result<PaymentVO> create(@Validated @RequestBody CreatePaymentDTO dto) {
        return Result.ok(paymentService.create(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "查询支付状态", description = "前端轮询此接口等待支付结果")
    @GetMapping("/{tradeNo}/status")
    public Result<PaymentVO> status(@PathVariable String tradeNo) {
        return Result.ok(paymentService.query(SecurityUtils.getUserId(), tradeNo));
    }

    @Operation(summary = "模拟支付成功", description = "演示用「我已支付」，接入真实渠道后删除")
    @PostMapping("/{tradeNo}/mock-pay")
    public Result<Void> mockPay(@PathVariable String tradeNo) {
        paymentService.mockPay(SecurityUtils.getUserId(), tradeNo);
        return Result.ok();
    }

    @Operation(summary = "支付渠道异步回调",
            description = "免登录白名单接口；按 tradeNo 幂等，成功后驱动订单进入「待发货」")
    @PostMapping("/callback")
    public Result<Void> callback(@Validated @RequestBody PayCallbackDTO dto) {
        paymentService.handleCallback(dto);
        return Result.ok();
    }
}
