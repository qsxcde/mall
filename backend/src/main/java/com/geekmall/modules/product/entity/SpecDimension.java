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
 * 商品规格维度 pms_spec_dimension：如「颜色」「尺寸」「内存」。
 *
 * <p>一个商品可有多个维度，维度下的取值见 {@link SpecValue}；
 * 维度与取值共同决定 SKU 组合（颜色 × 尺寸笛卡尔积）。</p>
 */
@Data
@TableName("pms_spec_dimension")
public class SpecDimension implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 所属店铺（商家端数据隔离维度） */
    private Long shopId;

    /** 维度名：颜色 / 尺寸 / 内存 */
    private String name;

    /** 展示排序，越小越前 */
    private Integer sort;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
