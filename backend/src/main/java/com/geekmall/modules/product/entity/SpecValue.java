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
 * 商品规格值 pms_spec_value：如「黑色」「XL」「512G」。
 *
 * <p>{@code image} 是该规格值的展示图（如颜色图），买家端维度联动选择时展示。</p>
 */
@Data
@TableName("pms_spec_value")
public class SpecValue implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属维度 ID */
    private Long dimensionId;

    /** 商品 ID（冗余，便于整商品读写） */
    private Long productId;

    /** 规格值：黑色 / XL / 512G */
    private String value;

    /** 该规格值的展示图（如颜色图） */
    private String image;

    /** 展示排序 */
    private Integer sort;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
