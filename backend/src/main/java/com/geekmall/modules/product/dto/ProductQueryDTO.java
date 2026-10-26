package com.geekmall.modules.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 商品列表 / 搜索查询条件，对应前端 CategoryView 与 SearchView。
 */
@Data
@Schema(description = "商品查询条件")
public class ProductQueryDTO implements Serializable {

    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer pageSize = 8;

    @Schema(description = "顶级分类 key")
    private String cat;

    @Schema(description = "子分类 key")
    private String sub;

    @Schema(description = "搜索关键词")
    private String keyword;

    @Schema(description = "品牌，多个用英文逗号分隔")
    private String brand;

    @Schema(description = "价格下限")
    private BigDecimal priceMin;

    @Schema(description = "价格上限")
    private BigDecimal priceMax;

    @Schema(description = "排序：default / sales / price_asc / price_desc / rating / new")
    private String sort;

    @Schema(description = "只看热销")
    private Boolean hot;

    @Schema(description = "只看新品")
    private Boolean isNew;

    /**
     * 分页深度上限：{@code page × pageSize ≤ 10 万}。
     *
     * <p>MyBatis-Plus 只限制了单页条数（{@code maxLimit=200}），拦不住
     * {@code page=100000} 这种超大 OFFSET 全表扫描。在参数绑定阶段直接拒绝，
     * 避免深分页请求把数据库连接与磁盘 IO 拖满。</p>
     */
    @JsonIgnore
    @AssertTrue(message = "分页过深，请缩小筛选条件后重试")
    public boolean isPageDepthAllowed() {
        int current = page == null ? 1 : page;
        int size = pageSize == null ? 8 : pageSize;
        return (long) current * size <= 100_000L;
    }
}
