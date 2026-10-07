package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端订单列表查询条件，对应前端 OrderListView。
 */
@Data
@Schema(description = "商家端订单查询")
public class MerchantOrderQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 8;

    @Schema(description = "状态：all/wait_pay/wait_ship/shipped/wait_review/done/closed/after")
    private String status = "all";

    private String keyword = "";

    @Schema(description = "下单时间范围：all/today/7/30")
    private String range = "all";

    @Schema(description = "支付方式")
    private String payWay = "all";

    @Schema(description = "金额区间：all/0-1000/1000-5000/5000-10000/10000-999999")
    private String amountRange = "all";

    @Schema(description = "排序：time_desc/time_asc/amount_desc/amount_asc")
    private String sort = "time_desc";
}
