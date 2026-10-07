package com.geekmall.modules.merchant.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.merchant.dto.MerchantLoginDTO;
import com.geekmall.modules.merchant.service.MerchantAuthService;
import com.geekmall.modules.merchant.vo.MerchantLoginVO;
import com.geekmall.modules.merchant.vo.MerchantProfileVO;
import com.geekmall.modules.merchant.vo.ShopVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端认证接口。
 *
 * <p>{@code /api/v1/merchant/auth/**} 为白名单（可匿名访问），其余商家域接口均需商家令牌。</p>
 */
@Tag(name = "10-商家认证", description = "商家登录 / 店铺信息 / 登出")
@RestController
@RequestMapping("/api/v1/merchant/auth")
@RequiredArgsConstructor
public class MerchantAuthController {

    private final MerchantAuthService merchantAuthService;

    @Operation(summary = "商家登录")
    @PostMapping("/login")
    public Result<MerchantLoginVO> login(@Valid @RequestBody MerchantLoginDTO dto) {
        return Result.ok(merchantAuthService.login(dto));
    }

    @Operation(summary = "当前店铺信息")
    @GetMapping("/shop")
    public Result<ShopVO> shop() {
        return Result.ok(merchantAuthService.currentShop());
    }

    @Operation(summary = "当前商家资料")
    @GetMapping("/profile")
    public Result<MerchantProfileVO> profile() {
        return Result.ok(merchantAuthService.currentProfile());
    }

    @Operation(summary = "商家登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        merchantAuthService.logout();
        return Result.ok();
    }
}
