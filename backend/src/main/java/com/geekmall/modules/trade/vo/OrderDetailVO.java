package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情出参，对应前端 OrderDetailView。
 */
@Data
@Schema(description = "订单详情")
public class OrderDetailVO implements Serializable {

    @Schema(description = "订单主体信息")
    private OrderVO order;

    /* ---------- 收货信息（下单时的地址快照） ---------- */

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    /* ---------- 支付信息 ---------- */

    private String payMethod;

    private LocalDateTime payTime;

    private String tradeNo;

    private String remark;

    private String cancelReason;

    private LocalDateTime cancelTime;

    @Schema(description = "待付款订单剩余支付秒数，为 0 表示不适用")
    private Long expireSecondsLeft;

    @Schema(description = "订单进度时间轴")
    private List<OrderTimelineVO> timeline;

    @Schema(description = "物流信息，未发货为 null")
    private LogisticsVO logistics;
}
