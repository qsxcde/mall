package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 商家端店铺优惠券行。
 */
@Data
@Schema(description = "商家端店铺优惠券")
public class MerchantCouponVO implements Serializable {

    private String id;

    private String name;

    private BigDecimal threshold;

    private BigDecimal amount;

    @Schema(description = "发行总量")
    private Integer total;

    @Schema(description = "已领取")
    private Integer taken;

    @Schema(description = "已核销（当前无核销明细表，返回 0）")
    private Integer used;

    private String status;

    private LocalDate endAt;
}
