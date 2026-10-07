package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算单明细中的抽样订单。
 */
@Data
@Schema(description = "结算单明细订单")
public class SettlementOrderVO implements Serializable {

    private String id;

    private OrderProductVO product;

    private Integer qty;

    @Schema(description = "商品金额")
    private BigDecimal goods;

    private BigDecimal commission;

    private BigDecimal service;

    @Schema(description = "实结金额")
    private BigDecimal settle;

    private LocalDateTime paidAt;
}
