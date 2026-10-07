package com.geekmall.modules.marketing.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.marketing.service.PointsService;
import com.geekmall.modules.marketing.vo.PointsExchangeVO;
import com.geekmall.modules.marketing.vo.PointsGoodsVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分商城接口，对应前端 PointsMallView。
 */
@Tag(name = "12-积分商城", description = "积分商品 / 兑换 / 兑换记录")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;

    @Operation(summary = "积分商品列表", description = "带「积分是否足够」标记，供前端置灰")
    @GetMapping("/goods")
    public Result<List<PointsGoodsVO>> goods() {
        return Result.ok(pointsService.goods(SecurityUtils.getUserId()));
    }

    @Operation(summary = "积分兑换", description = "扣积分 + 扣库存 + 写记录，同一事务")
    @PostMapping("/exchange/{goodsId}")
    public Result<PointsExchangeVO> exchange(@PathVariable Long goodsId) {
        return Result.ok(pointsService.exchange(SecurityUtils.getUserId(), goodsId));
    }

    @Operation(summary = "我的兑换记录")
    @GetMapping("/records")
    public Result<List<PointsExchangeVO>> records() {
        return Result.ok(pointsService.records(SecurityUtils.getUserId()));
    }
}
