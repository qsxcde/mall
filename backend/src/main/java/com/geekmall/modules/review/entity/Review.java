package com.geekmall.modules.review.entity;

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
 * 商品评价 pms_review。
 */
@Data
@TableName("pms_review")
public class Review implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺（商家端隔离维度） */
    private Long shopId;

    private Long userId;

    private String orderNo;

    private Long productId;

    /** 描述相符 */
    private Integer scoreDesc;

    /** 物流服务 */
    private Integer scoreLogistics;

    /** 服务态度 */
    private Integer scoreService;

    private String content;

    /** 图片地址，逗号分隔 */
    private String images;

    private Integer anonymous;

    /** 商家回复 */
    private String reply;

    /** 商家是否忽略 1 是 0 否 */
    private Integer ignored;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
