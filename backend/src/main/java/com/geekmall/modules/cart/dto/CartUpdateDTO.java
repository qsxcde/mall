package com.geekmall.modules.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 购物车变更入参：改数量 / 改勾选 / 全选。
 */
@Data
@Schema(description = "购物车变更")
public class CartUpdateDTO implements Serializable {

    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 99, message = "单项数量最多 99")
    private Integer qty;

    @Schema(description = "是否勾选")
    private Boolean checked;
}
