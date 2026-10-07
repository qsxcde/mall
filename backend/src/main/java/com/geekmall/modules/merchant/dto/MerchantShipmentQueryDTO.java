package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端发货单查询条件，对应前端 ShippingDeskView。
 */
@Data
@Schema(description = "商家端发货单查询")
public class MerchantShipmentQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 7;

    @Schema(description = "状态：all/late/printed/shipped/exc")
    private String status = "all";

    private String keyword = "";

    @Schema(description = "快递公司")
    private String express = "all";

    @Schema(description = "发货仓")
    private String warehouse = "all";

    @Schema(description = "排序：late/time_asc/time_desc")
    private String sort = "late";
}
