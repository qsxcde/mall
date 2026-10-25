package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

/**
 * 规格维度出参：维度名 + 取值列表（含展示图），供买家端维度联动选择器渲染。
 */
@Data
@Schema(description = "规格维度")
public class SpecDimensionVO implements Serializable {

    @Schema(description = "维度名：颜色 / 尺寸 / 内存")
    private String name;

    @Schema(description = "维度取值（含展示图）")
    private List<SpecValueVO> values;
}
