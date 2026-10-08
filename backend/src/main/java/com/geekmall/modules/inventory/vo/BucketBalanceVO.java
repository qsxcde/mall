package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 某商品的分桶余量全景：桶明细 + 与商品总库存的对账结果。
 */
@Data
@Schema(description = "库存桶余量全景")
public class BucketBalanceVO implements Serializable {

    private Long productId;

    @Schema(description = "商品总库存 pms_product.stock（权威总量）")
    private Integer productStock;

    @Schema(description = "各桶余量合计")
    private Integer bucketStockTotal;

    @Schema(description = "差额 = 商品总库存 − 桶合计；正常应为 0")
    private Integer delta;

    @Schema(description = "是否守恒（差额为 0）")
    private Boolean consistent;

    private Integer bucketCount;

    private List<BucketVO> buckets;
}
