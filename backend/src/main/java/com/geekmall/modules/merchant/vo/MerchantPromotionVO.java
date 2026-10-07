package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家端营销活动行。
 */
@Data
@Schema(description = "商家端营销活动")
public class MerchantPromotionVO implements Serializable {

    private String id;

    private String name;

    @Schema(description = "类型：discount/seckill/coupon/bundle/group/gift")
    private String type;

    @Schema(description = "状态：running/pending/paused/ended/audit")
    private String status;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private BigDecimal budget;

    private BigDecimal cost;

    private BigDecimal roi;

    private BigDecimal gmv;

    private Integer sold;

    @Schema(description = "参与商品数")
    private Integer joined;
}
