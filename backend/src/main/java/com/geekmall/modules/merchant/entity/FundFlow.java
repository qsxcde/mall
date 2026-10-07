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
 * 商家资金流水 mms_fund_flow。
 */
@Data
@TableName("mms_fund_flow")
public class FundFlow implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** commission / service / refund / settle */
    private String type;

    private String title;

    /** 提现请求号，用于幂等；非提现流水为 null */
    private String requestId;

    /** 金额（正数），方向由 direction 决定 */
    private BigDecimal amount;

    /** 1 入账 -1 出账 */
    private Integer direction;

    private LocalDateTime occupyTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
