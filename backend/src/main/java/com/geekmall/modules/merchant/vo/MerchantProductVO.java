package com.geekmall.modules.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家端商品行，字段对齐前端 ProductListView 的渲染需求。
 */
@Data
@Schema(description = "商家端商品")
public class MerchantProductVO implements Serializable {

    private Long id;

    @Schema(description = "商品编码")
    private String code;

    private String name;

    private String spec;

    @Schema(description = "分类名")
    private String cat;

    private String brand;

    private BigDecimal price;

    @Schema(description = "划线价")
    private BigDecimal listPrice;

    @Schema(description = "成本价")
    private BigDecimal cost;

    private Integer stock;

    @Schema(description = "安全库存线")
    private Integer safeStock;

    private Integer sales;

    private Integer views;

    private String cover;

    @Schema(description = "缩略图配色键")
    private String thumb;

    @Schema(description = "短标签")
    private String tag;

    /** 商家侧状态：on / ware / audit / sold / off / trash */
    private String status;

    @Schema(description = "多规格（当前商品表未落库 SKU，返回空集合）")
    private List<Object> skus;

    @Schema(description = "乐观锁版本号：编辑提交时须原样回传，用于识别并发修改")
    private Integer version;

    private LocalDateTime updatedAt;
}
