package com.geekmall.modules.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * SKU 与规格值关联 pms_sku_spec：记录一个 SKU 由哪些规格值组成。
 *
 * <p>{@code value_snapshot} 是规格值快照，规格值改名后历史 SKU 仍可追溯。</p>
 */
@Data
@TableName("pms_sku_spec")
public class SkuSpec implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    /** 商品 ID（冗余） */
    private Long productId;

    /** 规格值 ID */
    private Long specValueId;

    /** 维度 ID（冗余，便于按维度分组展示） */
    private Long dimensionId;

    /** 规格值快照 */
    private String valueSnapshot;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
