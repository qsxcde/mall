package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类出参（树形）。
 */
@Data
@Schema(description = "商品分类")
public class CategoryVO implements Serializable {

    private Long id;

    private String categoryKey;

    private String name;

    private String description;

    private String icon;

    @Schema(description = "子分类")
    private List<CategoryVO> children = new ArrayList<>();
}
