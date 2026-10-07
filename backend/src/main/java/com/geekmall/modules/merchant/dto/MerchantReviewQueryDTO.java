package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端评价查询条件，对应前端 ReviewView。
 */
@Data
@Schema(description = "商家端评价查询")
public class MerchantReviewQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 8;

    @Schema(description = "Tab：all/wait/replied/bad/good/pic")
    private String tab = "all";

    private String keyword = "";

    @Schema(description = "排序：time_desc/time_asc/rating_asc/rating_desc")
    private String sort = "time_desc";
}
