package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端售后工单查询条件，对应前端 AfterSaleView。
 */
@Data
@Schema(description = "商家端售后查询")
public class MerchantAftersaleQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 7;

    @Schema(description = "状态：all/pending/wait_return/wait_receive/done/rejected")
    private String status = "all";

    @Schema(description = "类型：all/refund/return/exchange/repair")
    private String type = "all";

    private String keyword = "";

    @Schema(description = "排序：apply_desc/apply_asc/amount_desc/deadline")
    private String sort = "apply_desc";
}
