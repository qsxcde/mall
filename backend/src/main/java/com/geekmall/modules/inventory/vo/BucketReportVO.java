package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存分桶报告：按维度汇总 + 守恒对账。
 */
@Data
@Schema(description = "库存分桶报告")
public class BucketReportVO implements Serializable {

    private Long productId;

    @Schema(description = "商品总库存")
    private Integer productStock;

    @Schema(description = "各桶余量合计")
    private Integer bucketStockTotal;

    @Schema(description = "差额 = 商品总库存 − 桶合计")
    private Integer delta;

    @Schema(description = "是否守恒")
    private Boolean consistent;

    @Schema(description = "桶总数")
    private Integer bucketCount;

    @Schema(description = "按维度汇总明细")
    private List<DimensionStat> dimensionStats;

    private LocalDateTime generatedAt;

    /**
     * 单维度统计。
     */
    @Data
    @Schema(description = "维度统计")
    public static class DimensionStat implements Serializable {

        private String dimension;

        private Integer stock;

        private Integer bucketCount;
    }
}
