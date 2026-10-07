package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家端订单行，字段对齐前端 OrderListView 的卡片与详情抽屉。
 */
@Data
@Schema(description = "商家端订单")
public class MerchantOrderVO implements Serializable {

    @Schema(description = "订单号")
    private String id;

    @Schema(description = "商家侧状态：wait_pay/wait_ship/shipped/wait_review/done/closed/after")
    private String status;

    @Schema(description = "商品（取订单首个明细）")
    private OrderProductVO product;

    private Integer qty;

    private BigDecimal goodsAmount;

    private BigDecimal shipFee;

    private BigDecimal discount;

    private BigDecimal payAmount;

    @Schema(description = "买家")
    private BuyerVO buyer;

    @Schema(description = "支付方式码：wechat/alipay/card/balance")
    private String payWay;

    @Schema(description = "支付方式文案，如「微信支付」")
    private String payWayLabel;

    @Schema(description = "发货仓库")
    private String warehouse;

    private String express;

    private String waybill;

    private String phone;

    private String address;

    @Schema(description = "买家留言/备注")
    private String note;

    @Schema(description = "商家备注")
    private String merchantNote;

    private LocalDateTime createdAt;

    private LocalDateTime paidAt;

    private LocalDateTime shipAt;

    private LocalDateTime recvAt;
}
