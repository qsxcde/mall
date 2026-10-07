package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品新增 / 编辑入参。
 */
@Data
@Schema(description = "商品保存")
public class ProductSaveDTO implements Serializable {

    @Schema(description = "商品 ID，为空表示新增")
    private Long id;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String spec;

    @Schema(description = "分类名")
    private String cat;

    private String brand;

    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0", message = "售价不能为负")
    private BigDecimal price;

    @Schema(description = "划线价")
    private BigDecimal listPrice;

    @Schema(description = "成本价")
    private BigDecimal cost;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    @Schema(description = "安全库存线")
    private Integer safeStock;

    @Schema(description = "商家侧状态：on/ware/audit/sold/off")
    private String status = "on";

    @Schema(description = "短标签")
    private String tag;

    private String cover;

    @Schema(description = "多规格（当前商品表未落库 SKU，仅接收不持久化）")
    private List<Map<String, Object>> skus;

    /**
     * 乐观锁版本号。
     *
     * <p>编辑场景必须回传列表中读到的值：若期间已被他人修改，服务端更新影响行数为 0 并提示刷新，
     * 避免后提交者整体覆盖前者的改动。新增时留空。</p>
     */
    @Schema(description = "乐观锁版本号（编辑时回传）")
    private Integer version;
}
