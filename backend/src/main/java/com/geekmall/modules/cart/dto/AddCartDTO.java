package com.geekmall.modules.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import lombok.Data;

/**
 * 加入购物车入参。
 */
@Data
public class AddCartDTO implements Serializable {

    @NotNull(message = "缺少商品 ID")
    private Long productId;

    /** 规格 SKU ID，多规格商品必传；为空表示无规格商品 */
    private Long skuId;

    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 99, message = "单项数量最多 99")
    private Integer qty = 1;

    /** 规格，可为空 */
    private String spec;
}
