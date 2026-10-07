package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 商家登录成功出参：令牌 + 店铺 + 账号资料。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "商家登录结果")
public class MerchantLoginVO implements Serializable {

    @Schema(description = "JWT 令牌")
    private String token;

    @Schema(description = "令牌类型")
    private String tokenType;

    @Schema(description = "有效期（秒）")
    private Long expiresIn;

    @Schema(description = "店铺信息")
    private ShopVO shop;

    @Schema(description = "账号资料")
    private MerchantProfileVO user;
}
