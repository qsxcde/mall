package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家账号资料出参。
 */
@Data
@Schema(description = "商家资料")
public class MerchantProfileVO implements Serializable {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    @Schema(description = "角色名")
    private String role;

    private Long shopId;

    private String shopName;
}
