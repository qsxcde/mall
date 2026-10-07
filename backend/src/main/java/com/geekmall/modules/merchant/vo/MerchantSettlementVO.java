package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商家端结算单行。
 */
@Data
@Schema(description = "商家端结算单")
public class MerchantSettlementVO implements Serializable {

    @Schema(description = "结算单号")
    private String id;

    @Schema(description = "账期文案")
    private String range;

    private BigDecimal gmv;

    private BigDecimal commission;

    private BigDecimal service;

    private BigDecimal refund;

    @Schema(description = "实结到账")
    private BigDecimal settle;

    @Schema(description = "实结率")
    private BigDecimal rate;

    @Schema(description = "settled/settling/pending")
    private String status;

    @Schema(description = "账期内订单数")
    private Long orderCount;
}
