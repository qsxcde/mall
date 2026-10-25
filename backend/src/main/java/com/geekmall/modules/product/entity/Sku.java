package com.geekmall.modules.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 商品 SKU pms_sku：一个规格组合一行，独立价格与库存。
 *
 * <p>词汇约定：本表即「规格 SKU」（{@code sku_id}），与 {@code inv_bucket_rule.granularity}
 * 中注释为「SKU（商品级）」的分桶粒度枚举无关。</p>
 *
 * <p>{@code spec_keys} 承载组合唯一性：排序后的规格值 ID 用 {@code |} 拼接（如 {@code 3|5}）；
 * {@code spec_desc} 是展示快照（如 {@code 黑色/XL}），规格值改名后历史仍可展示。</p>
 */
@Data
@TableName("pms_sku")
public class Sku implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 所属店铺（商家端数据隔离维度） */
    private Long shopId;

    /** SKU 编码，对外可见 */
    private String skuCode;

    /** 规格展示快照：黑色/XL */
    private String specDesc;

    /** 组合唯一键：排序后的规格值 ID 用 | 拼接 */
    private String specKeys;

    /** SKU 售价 */
    private BigDecimal price;

    /** SKU 成本价 */
    private BigDecimal cost;

    /** SKU 库存（= SUM(inv_bucket.stock WHERE sku_id)） */
    private Integer stock;

    /** SKU 安全库存线 */
    private Integer safeStock;

    /** 1 可售 0 停售 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
