package com.geekmall.modules.user.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.product.vo.ProductCardVO;
import com.geekmall.modules.user.dto.AddressDTO;
import com.geekmall.modules.user.dto.ProfileUpdateDTO;
import com.geekmall.modules.user.service.CollectionService;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import com.geekmall.modules.user.vo.HistoryItemVO;
import com.geekmall.modules.user.vo.SignVO;
import com.geekmall.modules.user.vo.UserProfileVO;
import com.geekmall.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户中心接口，对应前端 UserCenterView / MemberView。
 */
@Tag(name = "02-用户中心", description = "资料 / 收货地址 / 积分签到")
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CollectionService collectionService;

    @Operation(summary = "获取个人资料")
    @GetMapping("/profile")
    public Result<UserProfileVO> profile() {
        return Result.ok(userService.getProfile(SecurityUtils.getUserId()));
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Validated @RequestBody ProfileUpdateDTO dto) {
        userService.updateProfile(SecurityUtils.getUserId(), dto);
        return Result.ok();
    }

    @Operation(summary = "收货地址列表")
    @GetMapping("/addresses")
    public Result<List<AddressVO>> addresses() {
        return Result.ok(userService.listAddresses(SecurityUtils.getUserId()));
    }

    @Operation(summary = "新增收货地址")
    @PostMapping("/addresses")
    public Result<Long> addAddress(@Validated @RequestBody AddressDTO dto) {
        return Result.ok(userService.addAddress(SecurityUtils.getUserId(), dto));
    }

    @Operation(summary = "编辑收货地址")
    @PutMapping("/addresses/{id}")
    public Result<Void> updateAddress(@PathVariable Long id, @Validated @RequestBody AddressDTO dto) {
        userService.updateAddress(SecurityUtils.getUserId(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除收货地址")
    @DeleteMapping("/addresses/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id) {
        userService.deleteAddress(SecurityUtils.getUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "设为默认地址")
    @PutMapping("/addresses/{id}/default")
    public Result<Void> setDefaultAddress(@PathVariable Long id) {
        userService.setDefaultAddress(SecurityUtils.getUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "每日签到")
    @PostMapping("/sign-in")
    public Result<SignVO> signIn() {
        return Result.ok(userService.signIn(SecurityUtils.getUserId()));
    }

    @Operation(summary = "签到状态")
    @GetMapping("/sign-in")
    public Result<SignVO> signStatus() {
        return Result.ok(userService.signStatus(SecurityUtils.getUserId()));
    }

    /* ------------------------------ 我的收藏 ------------------------------ */

    @Operation(summary = "我的收藏")
    @GetMapping("/favorites")
    public Result<List<ProductCardVO>> favorites() {
        return Result.ok(collectionService.favorites(SecurityUtils.getUserId()));
    }

    @Operation(summary = "收藏 / 取消收藏", description = "返回 true 表示已收藏")
    @PostMapping("/favorites/{productId}/toggle")
    public Result<Boolean> toggleFavorite(@PathVariable Long productId) {
        return Result.ok(collectionService.toggleFavorite(SecurityUtils.getUserId(), productId));
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/favorites/{productId}")
    public Result<Void> removeFavorite(@PathVariable Long productId) {
        collectionService.removeFavorite(SecurityUtils.getUserId(), productId);
        return Result.ok();
    }

    @Operation(summary = "清空收藏")
    @DeleteMapping("/favorites")
    public Result<Void> clearFavorites() {
        collectionService.clearFavorites(SecurityUtils.getUserId());
        return Result.ok();
    }

    /* ------------------------------ 浏览足迹 ------------------------------ */

    @Operation(summary = "浏览足迹")
    @GetMapping("/history")
    public Result<List<HistoryItemVO>> history() {
        return Result.ok(collectionService.history(SecurityUtils.getUserId()));
    }

    @Operation(summary = "记录浏览", description = "进入商品详情时调用，同商品只更新时间")
    @PostMapping("/history/{productId}")
    public Result<Void> addHistory(@PathVariable Long productId) {
        collectionService.addHistory(SecurityUtils.getUserId(), productId);
        return Result.ok();
    }

    @Operation(summary = "删除足迹")
    @DeleteMapping("/history/{productId}")
    public Result<Void> removeHistory(@PathVariable Long productId) {
        collectionService.removeHistory(SecurityUtils.getUserId(), productId);
        return Result.ok();
    }

    @Operation(summary = "清空足迹")
    @DeleteMapping("/history")
    public Result<Void> clearHistory() {
        collectionService.clearHistory(SecurityUtils.getUserId());
        return Result.ok();
    }
}
