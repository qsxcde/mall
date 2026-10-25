package com.geekmall.modules.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 买家端 SKU 出参（商品详情页规格选择器用）。
 *
 * <p>刻意只暴露 {@code id / specDesc / price / stock}：成本价等内部字段不得进买家侧报文。</p>
 */
@Data
@Schema(description = "商品 SKU")
public class SkuVO implements Serializable {

    @Schema(description = "SKU ID，下单/加购时回传")
    private Long id;

    @Schema(description = "规格展示快照，如「黑色/XL」")
    private String specDesc;

    @Schema(description = "SKU 售价")
    private BigDecimal price;

    @Schema(description = "SKU 库存")
    private Integer stock;
}
