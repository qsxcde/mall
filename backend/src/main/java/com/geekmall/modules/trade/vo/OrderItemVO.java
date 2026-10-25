package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 订单明细出参。
 */
@Data
@Schema(description = "订单明细")
public class OrderItemVO implements Serializable {

    private Long productId;

    @Schema(description = "规格 SKU ID；0 = 无规格商品 / 历史明细")
    private Long skuId;

    private String title;

    private String cover;

    private String spec;

    private BigDecimal price;

    private Integer qty;

    @Schema(description = "小计")
    private BigDecimal amount;
}
