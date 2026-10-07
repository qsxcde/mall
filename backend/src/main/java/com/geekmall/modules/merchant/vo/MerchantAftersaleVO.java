package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商家端售后工单行，字段对齐前端 AfterSaleView 与详情抽屉。
 */
@Data
@Schema(description = "商家端售后工单")
public class MerchantAftersaleVO implements Serializable {

    private String id;

    @Schema(description = "关联订单号")
    private String orderId;

    @Schema(description = "类型：refund/return/exchange/repair")
    private String type;

    @Schema(description = "商家侧状态：pending/wait_return/wait_receive/done/rejected")
    private String status;

    private String reason;

    private BuyerVO buyer;

    private OrderProductVO product;

    private Integer qty;

    private BigDecimal orderAmount;

    private BigDecimal refundAmount;

    private LocalDateTime applyAt;

    @Schema(description = "处理时限，已终结为 null")
    private LocalDateTime deadline;

    @Schema(description = "寄回快递（当前未落库，返回空）")
    private String returnExpress;

    @Schema(description = "寄回运单号（当前未落库，返回空）")
    private String returnWaybill;

    private String rejectReason;

    @Schema(description = "凭证图片数量")
    private Integer images;

    private String phone;

    @Schema(description = "进度时间轴")
    private List<Map<String, Object>> timeline;
}
