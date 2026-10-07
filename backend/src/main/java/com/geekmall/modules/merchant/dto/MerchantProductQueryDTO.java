package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家端商品列表查询条件，对应前端 ProductListView。
 */
@Data
@Schema(description = "商家端商品查询")
public class MerchantProductQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 8;

    @Schema(description = "商家侧状态：all/on/ware/audit/sold/off/trash")
    private String status = "all";

    @Schema(description = "分类名")
    private String cat = "all";

    @Schema(description = "品牌名")
    private String brand = "all";

    @Schema(description = "库存状态：all/warn/out/plenty/normal")
    private String stock = "all";

    private String keyword = "";

    @Schema(description = "排序：update_desc/sales_desc/views_desc/price_desc/price_asc/stock_asc")
    private String sort = "update_desc";
}
