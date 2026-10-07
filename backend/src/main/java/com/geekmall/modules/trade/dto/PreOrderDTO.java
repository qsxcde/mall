package com.geekmall.modules.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 结算页试算入参。
 */
@Data
@Schema(description = "结算试算")
public class PreOrderDTO implements Serializable {

    @Schema(description = "要结算的购物车项 ID；为空则使用全部已勾选项")
    private List<Long> cartItemIds;

    @Schema(description = "配送方式：standard / express / same-day")
    private String shippingType;

    @Schema(description = "选中的用户优惠券 ID")
    private Long couponId;
}
