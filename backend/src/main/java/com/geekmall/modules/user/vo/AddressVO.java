package com.geekmall.modules.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 收货地址出参。
 */
@Data
@Schema(description = "收货地址")
public class AddressVO implements Serializable {

    private Long id;

    private String name;

    private String phone;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "区/县")
    private String district;

    @Schema(description = "详细地址")
    private String detail;

    @Schema(description = "是否默认地址：1 是 0 否")
    private Integer isDefault;

    @Schema(description = "完整地址（省市区 + 详细）")
    private String fullAddress;
}
