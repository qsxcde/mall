package com.geekmall.modules.cart.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车汇总：供顶栏角标与结算栏使用。
 */
@Data
@Schema(description = "购物车汇总")
public class CartSummaryVO implements Serializable {

    @Schema(description = "购物车总件数")
    private Integer totalQty;

    @Schema(description = "已勾选件数")
    private Integer checkedQty;

    @Schema(description = "已勾选金额")
    private BigDecimal checkedAmount;

    @Schema(description = "是否全选")
    private Boolean allChecked;

    @Schema(description = "购物车行数")
    private Integer itemCount;
}
