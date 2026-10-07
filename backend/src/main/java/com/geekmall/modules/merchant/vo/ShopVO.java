package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 店铺信息出参，对齐前端 merchant store 的 shop 结构。
 */
@Data
@Schema(description = "店铺信息")
public class ShopVO implements Serializable {

    private Long id;

    private String name;

    private String logo;

    private String level;

    @Schema(description = "是否已认证")
    private Boolean verified;

    @Schema(description = "今日目标成交额")
    private BigDecimal todayTarget;
}
