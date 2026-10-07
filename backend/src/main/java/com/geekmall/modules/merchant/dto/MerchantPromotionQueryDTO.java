package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端营销活动查询条件，对应前端 MarketingView。
 */
@Data
@Schema(description = "商家端营销查询")
public class MerchantPromotionQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 6;

    @Schema(description = "状态：all/running/pending/paused/ended/audit")
    private String status = "all";

    @Schema(description = "类型：all/discount/seckill/coupon/bundle/group/gift")
    private String type = "all";

    private String keyword = "";

    @Schema(description = "排序：gmv_desc/roi_desc/cost_desc/start_desc")
    private String sort = "gmv_desc";
}
