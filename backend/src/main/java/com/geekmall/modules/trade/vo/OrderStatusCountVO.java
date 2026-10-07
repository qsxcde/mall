package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单状态计数，对应前端 OrdersView 顶部状态概览卡。
 */
@Data
@Schema(description = "订单状态计数")
public class OrderStatusCountVO implements Serializable {

    @Schema(description = "全部订单数")
    private Long all;

    @Schema(description = "待付款")
    private Long pay;

    @Schema(description = "待发货")
    private Long ship;

    @Schema(description = "待收货")
    private Long recv;

    @Schema(description = "待评价")
    private Long cmt;
}
