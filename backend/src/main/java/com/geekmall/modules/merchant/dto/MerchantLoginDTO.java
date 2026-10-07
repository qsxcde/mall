package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端登录入参。
 */
@Data
@Schema(description = "商家登录")
public class MerchantLoginDTO implements Serializable {

    @NotBlank(message = "账号不能为空")
    @Schema(description = "登录账号")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "登录密码")
    private String password;
}
