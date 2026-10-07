package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家端发货单行，字段对齐前端 ShippingDeskView。
 */
@Data
@Schema(description = "商家端发货单")
public class MerchantShipmentVO implements Serializable {

    @Schema(description = "订单号（发货单以订单为源）")
    private String id;

    @Schema(description = "状态：wait 待打单/printed 已打单/shipped 已发货/exc 异常")
    private String status;

    private OrderProductVO product;

    private Integer qty;

    private BigDecimal amount;

    private BuyerVO buyer;

    private String phone;

    @Schema(description = "收货地区 + 详细地址")
    private String area;

    private String province;

    @Schema(description = "发货仓库")
    private String warehouse;

    private String express;

    private String waybill;

    private LocalDateTime paidAt;

    @Schema(description = "发货时限（付款 + 24 小时）")
    private LocalDateTime deadline;

    @Schema(description = "距超时的剩余小时数，非待发货返回 null")
    private Double hoursLeft;

    private LocalDateTime printedAt;

    private LocalDateTime shippedAt;
}
