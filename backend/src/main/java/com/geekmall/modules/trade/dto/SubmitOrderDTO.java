package com.geekmall.modules.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 提交订单入参（确认订单页 → 收银台）。
 */
@Data
@Schema(description = "提交订单")
public class SubmitOrderDTO implements Serializable {

    @NotNull(message = "请选择收货地址")
    private Long addressId;

    @Schema(description = "要结算的购物车项 ID；为空则使用全部已勾选项")
    private List<Long> cartItemIds;

    @Schema(description = "配送方式：standard 标准 / express 顺丰 / same-day 次日达")
    private String shippingType = "standard";

    @Schema(description = "使用的用户优惠券 ID，可不填")
    private Long couponId;

    @Schema(description = "支付方式：wechat / alipay / card / balance")
    private String payMethod = "wechat";

    @Size(max = 255, message = "备注过长")
    private String remark;

    /**
     * 幂等键：同一次提交重试时保持不变，服务端据此避免重复下单。
     * 前端建议在下单页生成 UUID。
     */
    @Schema(description = "幂等键，客户端生成 UUID")
    private String requestId;
}
