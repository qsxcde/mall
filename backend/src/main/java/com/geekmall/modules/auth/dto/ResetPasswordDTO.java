package com.geekmall.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 找回密码入参（对应前端 ForgotView 三步流程的最后一步）。
 */
@Data
public class ResetPasswordDTO implements Serializable {

    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;

    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位")
    private String newPassword;
}
