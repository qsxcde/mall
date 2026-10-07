package com.geekmall.modules.aftersale.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后出参：列表与详情共用。
 */
@Data
@Schema(description = "售后申请")
public class AfterSaleVO implements Serializable {

    private Long id;

    private String orderNo;

    @Schema(description = "关联订单的首件商品名")
    private String productTitle;

    private String type;

    private String typeName;

    private String reason;

    private String content;

    private List<String> images;

    private String phone;

    private BigDecimal amount;

    @Schema(description = "0 处理中 1 已完成 2 已取消")
    private Integer status;

    private String statusText;

    private LocalDateTime createTime;

    @Schema(description = "处理进度时间轴，仅详情返回")
    private List<AfterSaleStepVO> steps;
}
