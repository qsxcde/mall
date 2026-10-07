package com.geekmall.modules.trade.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.trade.service.TradeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联调用的模拟接口，仅在 {@code mall.mock.enabled=true} 时注册（dev 默认开启，prod 关闭）。
 *
 * <p>存在的原因：发货是商家后台的职责，骨架阶段没有后台，而前端要跑通
 * 「支付 → 发货 → 确认收货 → 评价 → 流转到已完成」这条链，需要一个触发点。
 * 生产环境务必保持 {@code mall.mock.enabled=false}。</p>
 */
@Tag(name = "99-联调模拟(仅dev)", description = "仅开发环境注册的模拟接口")
@RestController
@RequestMapping("/api/v1/trade/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mock.enabled", havingValue = "true")
public class TradeMockController {

    private final TradeService tradeService;

    @Operation(summary = "模拟商家发货", description = "待发货 → 待收货；生产环境应由商家后台承接")
    @PostMapping("/ship/{orderNo}")
    public Result<Void> ship(@PathVariable String orderNo) {
        tradeService.ship(orderNo);
        return Result.ok();
    }
}
