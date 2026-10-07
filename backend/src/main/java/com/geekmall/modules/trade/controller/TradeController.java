package com.geekmall.modules.trade.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.trade.dto.CancelOrderDTO;
import com.geekmall.modules.trade.dto.PreOrderDTO;
import com.geekmall.modules.trade.dto.SubmitOrderDTO;
import com.geekmall.modules.trade.service.TradeService;
import com.geekmall.modules.trade.vo.PreOrderVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易接口：结算试算、下单、订单操作。对应前端 CheckoutView 与订单详情的各类按钮。
 */
@Tag(name = "07-交易", description = "结算试算 / 下单 / 取消 / 确认收货 / 提醒发货")
@RestController
@RequestMapping("/api/v1/trade")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;

    @Operation(summary = "结算试算", description = "返回商品、金额、地址、配送方式、可用券与支付方式")
    @PostMapping("/pre-order")
    public Result<PreOrderVO> preOrder(@RequestBody(required = false) PreOrderDTO dto) {
        return Result.ok(tradeService.preOrder(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "提交订单", description = "幂等下单，成功后返回订单号")
    @PostMapping("/orders")
    public Result<String> submit(@Validated @RequestBody SubmitOrderDTO dto) {
        return Result.ok(tradeService.submit(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "取消订单")
    @PostMapping("/orders/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo,
                               @Validated @RequestBody(required = false) CancelOrderDTO dto) {
        tradeService.cancel(SecurityUtils.getUserId(), orderNo, dto == null ? null : dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "确认收货")
    @PostMapping("/orders/{orderNo}/confirm")
    public Result<Void> confirm(@PathVariable String orderNo) {
        tradeService.confirmReceipt(SecurityUtils.getUserId(), orderNo);
        return Result.ok();
    }

    @Operation(summary = "提醒发货")
    @PostMapping("/orders/{orderNo}/remind")
    public Result<Void> remind(@PathVariable String orderNo) {
        tradeService.remindDelivery(SecurityUtils.getUserId(), orderNo);
        return Result.ok();
    }
}
