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
 * 店铺 mms_shop。
 */
@Data
@TableName("mms_shop")
public class Shop implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String logo;

    /** 卖家等级，如「金牌卖家」 */
    private String level;

    /** 1 已认证 0 未认证 */
    private Integer verified;

    /** 今日目标成交额 */
    private BigDecimal todayTarget;

    /** 可用余额（提现走条件扣减，禁止先查后改） */
    private BigDecimal balance;

    /** 1 正常 0 停业 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
