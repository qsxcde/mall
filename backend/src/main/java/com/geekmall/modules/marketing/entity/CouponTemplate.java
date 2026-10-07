package com.geekmall.modules.marketing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 优惠券模板 mkt_coupon_template。
 */
@Data
@TableName("mkt_coupon_template")
public class CouponTemplate implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** full 满减 / percent 折扣 / shipping 免运费 */
    private String type;

    private BigDecimal amount;

    /** 单位：¥ 或 折 */
    private String unit;

    /** 使用门槛 */
    private BigDecimal threshold;

    private String scope;

    private LocalDate validFrom;

    private LocalDate validTo;

    private Integer total;

    private Integer stock;

    private Integer perLimit;

    private Integer percent;

    private Integer limited;

    private Integer soldout;

    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
