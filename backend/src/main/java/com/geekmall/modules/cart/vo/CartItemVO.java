package com.geekmall.modules.cart.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车项出参，对应前端 CartView。
 */
@Data
@Schema(description = "购物车项")
public class CartItemVO implements Serializable {

    private Long id;

    private Long productId;

    private String title;

    private String cover;

    private BigDecimal price;

    private String spec;

    private Integer qty;

    @Schema(description = "是否勾选")
    private Boolean checked;

    @Schema(description = "小计金额 = 单价 × 数量")
    private BigDecimal amount;
}
