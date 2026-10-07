package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商品卡片出参，对齐前端 ProductCard 组件所需字段。
 */
@Data
@Schema(description = "商品卡片")
public class ProductCardVO implements Serializable {

    private Long id;

    private String title;

    private String cover;

    private BigDecimal price;

    private BigDecimal oldPrice;

    @Schema(description = "规格描述")
    private String spec;

    private Integer sales;

    private BigDecimal rating;

    private String brand;

    @Schema(description = "所属分类名")
    private String cat;

    @Schema(description = "标签列表")
    private List<String> tags;
}
