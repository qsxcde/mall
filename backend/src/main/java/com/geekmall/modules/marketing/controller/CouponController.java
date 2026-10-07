package com.geekmall.modules.marketing.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.marketing.vo.CouponTemplateVO;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券接口，对应前端 CouponView 与个人中心「我的优惠券」。
 */
@Tag(name = "10-优惠券", description = "领券中心 / 领取 / 我的优惠券")
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "领券中心券列表", description = "白名单接口，未登录也可浏览；登录后会标记是否已领取")
    @GetMapping("/templates")
    public Result<List<CouponTemplateVO>> templates(@RequestParam(required = false) String type) {
        return Result.ok(couponService.listTemplates(type, SecurityUtils.getUserIdOrNull()));
    }

    @Operation(summary = "领取优惠券")
    @PostMapping("/claim/{templateId}")
    public Result<Void> claim(@PathVariable Long templateId) {
        couponService.claim(SecurityUtils.getUserId(), templateId);
        return Result.ok();
    }

    @Operation(summary = "我的优惠券", description = "status：0 未使用 / 1 已使用 / 2 已过期，不传返回全部")
    @GetMapping("/mine")
    public Result<List<UserCouponVO>> mine(@RequestParam(required = false) Integer status) {
        return Result.ok(couponService.myCoupons(SecurityUtils.getUserId(), status));
    }
}
