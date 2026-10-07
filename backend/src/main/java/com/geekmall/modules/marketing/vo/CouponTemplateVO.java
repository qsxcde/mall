package com.geekmall.modules.marketing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 领券中心券模板出参，对应前端 CouponView。
 */
@Data
@Schema(description = "优惠券模板")
public class CouponTemplateVO implements Serializable {

    private Long id;

    private String name;

    @Schema(description = "full 满减 / percent 折扣 / shipping 免运费")
    private String type;

    private BigDecimal amount;

    @Schema(description = "单位：¥ 或 折")
    private String unit;

    private BigDecimal threshold;

    private String scope;

    private LocalDate validFrom;

    private LocalDate validTo;

    private Integer total;

    private Integer stock;

    private Integer perLimit;

    @Schema(description = "已领百分比")
    private Integer percent;

    private Boolean limited;

    @Schema(description = "是否已抢光")
    private Boolean soldout;

    @Schema(description = "当前用户是否已领取（未登录为 false）")
    private Boolean claimed;
}
