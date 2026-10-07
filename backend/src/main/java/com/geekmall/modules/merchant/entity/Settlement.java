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
 * 结算单 mms_settlement。
 *
 * <p>口径自洽：实结 = 成交额 - 平台佣金 - 支付服务费 - 退款扣减。</p>
 */
@Data
@TableName("mms_settlement")
public class Settlement implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String settleNo;

    /** 账期文案，如「10-01 ~ 10-05」 */
    private String rangeLabel;

    private BigDecimal gmv;

    private BigDecimal commission;

    private BigDecimal service;

    private BigDecimal refund;

    /** 实结到账 */
    private BigDecimal settle;

    /** 实结率 */
    private BigDecimal rate;

    /** settled / settling / pending */
    private String status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
