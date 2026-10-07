package com.geekmall.modules.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单出参（列表 / 详情共用）。
 *
 * <p>除 items 外，还提供首件商品的扁平字段，方便列表页直接渲染。</p>
 */
@Data
@Schema(description = "订单")
public class OrderVO implements Serializable {

    private Long id;

    private String no;

    private Integer status;

    @Schema(description = "状态文案，如「待付款」")
    private String statusText;

    private BigDecimal goodsAmount;

    private BigDecimal shippingFee;

    private BigDecimal discount;

    private BigDecimal payAmount;

    @Schema(description = "商品总件数")
    private Integer totalQty;

    private LocalDateTime createTime;

    private List<OrderItemVO> items;

    /* ---------- 首件商品扁平字段（列表页渲染用） ---------- */

    private Long productId;

    private String productTitle;

    private String productCover;

    private String spec;

    private BigDecimal price;

    private Integer qty;

    /* ---------- 按钮可用性由后端统一判断，避免前端重复实现状态机 ---------- */

    private Boolean canCancel;

    private Boolean canPay;

    private Boolean canConfirm;

    private Boolean canReview;

    private Boolean canAfterSale;
}
