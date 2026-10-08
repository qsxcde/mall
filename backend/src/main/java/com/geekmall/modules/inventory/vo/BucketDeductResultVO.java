package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 出库结果：记录了「这次扣了哪些桶、各扣多少」，是回滚还回原桶的依据。
 */
@Data
@Schema(description = "分桶出库结果")
public class BucketDeductResultVO implements Serializable {

    private String orderNo;

    private Long productId;

    @Schema(description = "请求出库数量")
    private Integer requestedQty;

    @Schema(description = "实际扣减数量")
    private Integer deductedQty;

    @Schema(description = "本次命中的桶明细")
    private List<Detail> details;

    /**
     * 单个桶的扣减明细。
     */
    @Data
    @Schema(description = "桶扣减明细")
    public static class Detail implements Serializable {

        private Long bucketId;

        private String dimension;

        private String dimensionValue;

        private Integer qty;

        private Integer stockAfter;
    }
}
