package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细出参。
 */
@Data
@Schema(description = "订单明细")
public class OrderItemVO implements Serializable {

    private Long productId;

    private String title;

    private String cover;

    private String spec;

    private BigDecimal price;

    private Integer qty;

    @Schema(description = "小计")
    private BigDecimal amount;
}
