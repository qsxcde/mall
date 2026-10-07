package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 首页楼层聚合出参：一次请求渲染首页，减少前端请求数。
 */
@Data
@Schema(description = "首页楼层数据")
public class HomeFloorVO implements Serializable {

    @Schema(description = "分类导航")
    private List<CategoryVO> categories;

    @Schema(description = "热销榜")
    private List<ProductCardVO> hotProducts;

    @Schema(description = "新品首发")
    private List<ProductCardVO> newProducts;
}
