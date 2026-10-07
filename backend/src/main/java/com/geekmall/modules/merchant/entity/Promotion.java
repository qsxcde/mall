package com.geekmall.modules.merchant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家营销活动 mms_promotion。
 */
@Data
@TableName("mms_promotion")
public class Promotion implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String name;

    /** discount / seckill / coupon / bundle / group / gift */
    private String type;

    /** running / pending / paused / ended / audit */
    private String status;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private BigDecimal budget;

    private BigDecimal cost;

    private BigDecimal roi;

    private BigDecimal gmv;

    private Integer sold;

    /** 参与商品数 */
    private Integer joined;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
