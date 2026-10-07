package com.geekmall.modules.trade.controller;

import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.Result;
import com.geekmall.modules.trade.dto.OrderQueryDTO;
import com.geekmall.modules.trade.service.OrderService;
import com.geekmall.modules.trade.vo.LogisticsVO;
import com.geekmall.modules.trade.vo.OrderDetailVO;
import com.geekmall.modules.trade.vo.OrderStatusCountVO;
import com.geekmall.modules.trade.vo.OrderVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单查询接口，对应前端 OrdersView / OrderDetailView / LogisticsView。
 */
@Tag(name = "08-订单", description = "订单列表 / 状态计数 / 详情 / 物流")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "我的订单列表", description = "支持状态筛选与订单号/商品名搜索")
    @GetMapping
    public Result<PageResult<OrderVO>> page(@Valid @ModelAttribute OrderQueryDTO query) {
        return Result.ok(orderService.page(SecurityUtils.getUserId(), query));
    }

    @Operation(summary = "各状态订单数量", description = "对应前端顶部状态概览卡")
    @GetMapping("/status-counts")
    public Result<OrderStatusCountVO> statusCounts() {
        return Result.ok(orderService.statusCounts(SecurityUtils.getUserId()));
    }

    @Operation(summary = "订单详情", description = "含收货信息、支付信息、进度时间轴、物流摘要")
    @GetMapping("/{orderNo}")
    public Result<OrderDetailVO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.detail(SecurityUtils.getUserId(), orderNo));
    }

    @Operation(summary = "物流跟踪")
    @GetMapping("/{orderNo}/logistics")
    public Result<LogisticsVO> logistics(@PathVariable String orderNo) {
        return Result.ok(orderService.logistics(SecurityUtils.getUserId(), orderNo));
    }
}
