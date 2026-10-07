package com.geekmall.modules.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品分类表 pms_category（parent_id = 0 表示顶级分类）。
 */
@Data
@TableName("pms_category")
public class Category implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父级 ID，0 为顶级 */
    private Long parentId;

    /** 分类唯一标识，如 phone / phone-pro */
    private String categoryKey;

    private String name;

    private String description;

    private String icon;

    private Integer sort;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
