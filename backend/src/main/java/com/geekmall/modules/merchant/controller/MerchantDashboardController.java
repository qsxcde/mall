package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.service.MerchantDashboardService;
import com.geekmall.modules.merchant.vo.NavBadgesVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商家经营概览 / 数据看板 / 角标接口，对应前端 OverviewView / AnalyticsView 与侧栏角标。
 */
@Tag(name = "11-商家经营", description = "角标 / 经营概览 / 数据看板")
@PreAuthorize("hasRole('MERCHANT')")
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class MerchantDashboardController {

    private final MerchantDashboardService dashboardService;

    @Operation(summary = "侧栏导航角标")
    @GetMapping("/nav/badges")
    public Result<NavBadgesVO> navBadges() {
        return Result.ok(dashboardService.navBadges());
    }

    @Operation(summary = "经营概览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(dashboardService.overview());
    }

    @Operation(summary = "数据看板")
    @GetMapping("/analytics")
    public Result<Map<String, Object>> analytics() {
        return Result.ok(dashboardService.analytics());
    }

    @Operation(summary = "近 N 天日序列")
    @GetMapping("/analytics/daily")
    public Result<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "30") int days) {
        return Result.ok(dashboardService.dailySeries(days));
    }

    @Operation(summary = "库存预警商品")
    @GetMapping("/product/stock-alerts")
    public Result<List<Map<String, Object>>> stockAlerts() {
        return Result.ok(dashboardService.stockAlerts());
    }
}
