package com.geekmall.modules.auth.controller;

import com.geekmall.common.result.Result;
import com.geekmall.modules.auth.dto.LoginDTO;
import com.geekmall.modules.auth.dto.RegisterDTO;
import com.geekmall.modules.auth.dto.ResetPasswordDTO;
import com.geekmall.modules.auth.dto.SendSmsCodeDTO;
import com.geekmall.modules.auth.dto.SmsLoginDTO;
import com.geekmall.modules.auth.service.AuthService;
import com.geekmall.modules.auth.vo.LoginVO;
import com.geekmall.modules.auth.vo.SmsCodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口，对应前端 LoginView / ForgotView。
 */
@Tag(name = "01-认证", description = "注册 / 登录 / 验证码 / 找回密码")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "密码登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Validated @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @Operation(summary = "短信验证码登录")
    @PostMapping("/login/sms")
    public Result<LoginVO> smsLogin(@Validated @RequestBody SmsLoginDTO dto) {
        return Result.ok(authService.smsLogin(dto));
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Validated @RequestBody RegisterDTO dto) {
        authService.register(dto);
        return Result.ok();
    }

    @Operation(summary = "发送短信验证码")
    @PostMapping("/sms-code")
    public Result<SmsCodeVO> sendSmsCode(@Validated @RequestBody SendSmsCodeDTO dto) {
        return Result.ok(authService.sendSmsCode(dto.getPhone(), dto.getScene()));
    }

    @Operation(summary = "重置密码")
    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Validated @RequestBody ResetPasswordDTO dto) {
        authService.resetPassword(dto);
        return Result.ok();
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }
}
