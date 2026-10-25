package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/**
 * SKU 编辑回显行：在买家侧 {@link SkuVO} 之上补「组合映射」与成本价，
 * 供商家端规格矩阵编辑器还原组合（并集反推维度）。
 *
 * <p>含成本价，<b>只允许出现在商家端报文</b>，不得进买家侧。</p>
 */
@Data
@Schema(description = "SKU 编辑回显行")
public class SkuEditVO implements Serializable {

    @Schema(description = "SKU ID")
    private Long id;

    @Schema(description = "规格展示快照，如「黑色/XL」")
    private String specDesc;

    @Schema(description = "规格值组合：维度名 → 规格值")
    private Map<String, String> specValues;

    @Schema(description = "SKU 售价")
    private BigDecimal price;

    @Schema(description = "SKU 成本价（仅商家端可见）")
    private BigDecimal cost;

    @Schema(description = "SKU 库存")
    private Integer stock;
}
