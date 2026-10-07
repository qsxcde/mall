package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家端评价行，字段对齐前端 ReviewView。
 */
@Data
@Schema(description = "商家端评价")
public class MerchantReviewVO implements Serializable {

    private String id;

    @Schema(description = "关联订单号")
    private String orderId;

    @Schema(description = "综合评分（三维度均值，四舍五入）")
    private Integer rating;

    @Schema(description = "是否差评（≤2 星）")
    private Boolean isBad;

    @Schema(description = "是否好评（≥4 星）")
    private Boolean isGood;

    private String content;

    @Schema(description = "图片数量")
    private Integer images;

    private List<String> tags;

    private OrderProductVO product;

    private BuyerVO buyer;

    @Schema(description = "规格")
    private String sku;

    @Schema(description = "状态：wait/replied/ignored")
    private String status;

    private String reply;

    private LocalDateTime repliedAt;

    @Schema(description = "有用数（暂无数据源，返回 0）")
    private Integer helpful;

    private LocalDateTime createdAt;
}
