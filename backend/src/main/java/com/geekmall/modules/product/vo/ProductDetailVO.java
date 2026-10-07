package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情出参，对应前端 ProductView。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "商品详情")
public class ProductDetailVO extends ProductCardVO {

    private Long categoryId;

    private String categoryKey;

    private String parentKey;

    private Long brandId;

    @Schema(description = "商品图集")
    private List<String> images;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "是否热销")
    private Boolean hot;

    @Schema(description = "是否新品")
    private Boolean isNew;

    @Schema(description = "库存（SKU 维度的汇总，骨架阶段取自商品主表）")
    private Integer stock;

    @Schema(description = "详情页展示用的价格，便于前端直接渲染")
    private BigDecimal displayPrice;
}
