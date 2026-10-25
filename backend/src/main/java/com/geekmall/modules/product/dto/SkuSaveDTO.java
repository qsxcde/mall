package com.geekmall.modules.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/**
 * SKU 保存行：一个规格组合的独立价格与库存。
 *
 * <p>{@code specValues} 是「维度名 → 规格值」映射（如 {@code {"颜色":"黑色","尺寸":"XL"}}），
 * 维度与取值本身由 {@link SpecDimensionSaveDTO} 声明；服务端据此生成
 * {@code spec_desc}（黑色/XL）与组合唯一键 {@code spec_keys}。</p>
 */
@Data
@Schema(description = "SKU 保存")
public class SkuSaveDTO implements Serializable {

    @Schema(description = "规格值组合：维度名 → 规格值")
    private Map<String, String> specValues;

    @NotNull(message = "SKU 售价不能为空")
    @DecimalMin(value = "0", message = "SKU 售价不能为负")
    @Schema(description = "SKU 售价")
    private BigDecimal price;

    @Min(value = 0, message = "SKU 库存不能为负")
    @Schema(description = "SKU 库存")
    private Integer stock;

    @DecimalMin(value = "0", message = "SKU 成本价不能为负")
    @Schema(description = "SKU 成本价")
    private BigDecimal cost;
}
