package com.geekmall.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 密码登录入参。
 */
@Data
@Schema(description = "密码登录")
public class LoginDTO implements Serializable {

    @NotBlank(message = "请输入账号")
    @Schema(description = "账号或手机号", example = "13800000000")
    private String account;

    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位")
    @Schema(description = "密码", example = "123456")
    private String password;

    @Schema(description = "记住我")
    private Boolean remember = false;
}
