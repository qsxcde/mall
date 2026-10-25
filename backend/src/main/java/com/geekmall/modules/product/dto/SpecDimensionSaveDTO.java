package com.geekmall.modules.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

/**
 * 规格维度保存入参：如「颜色」及其取值「黑色 / 白色」。
 *
 * <p>与 {@link SkuSaveDTO} 配套：本结构定义「维度 + 取值」，SKU 行用
 * 「维度名 → 规格值」引用它们组合出笛卡尔积。</p>
 */
@Data
@Schema(description = "规格维度保存")
public class SpecDimensionSaveDTO implements Serializable {

    @NotBlank(message = "维度名不能为空")
    @Schema(description = "维度名：颜色 / 尺寸 / 内存")
    private String name;

    @NotEmpty(message = "维度取值不能为空")
    @Valid
    @Schema(description = "维度取值列表")
    private List<Item> values;

    /**
     * 单个规格值。
     */
    @Data
    @Schema(description = "规格值保存")
    public static class Item implements Serializable {

        @NotBlank(message = "规格值不能为空")
        @Schema(description = "规格值：黑色 / XL / 512G")
        private String value;

        @Schema(description = "该规格值的展示图（如颜色图）")
        private String image;

        @Schema(description = "展示排序，越小越前")
        private Integer sort;
    }
}
