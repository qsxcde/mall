package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 订单内嵌的商品快照。
 */
@Data
@Schema(description = "订单商品快照")
public class OrderProductVO implements Serializable {

    private Long id;

    @Schema(description = "规格 SKU ID；0 = 无规格商品 / 历史明细")
    private Long skuId;

    private String name;

    private String cover;

    private String spec;

    private BigDecimal price;
}
