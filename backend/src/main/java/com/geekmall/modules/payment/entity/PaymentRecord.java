package com.geekmall.modules.payment.entity;

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
 * 支付单 pay_payment_record。
 */
@Data
@TableName("pay_payment_record")
public class PaymentRecord implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 支付流水号，唯一索引保证回调幂等 */
    private String tradeNo;

    private String orderNo;

    private Long userId;

    private String payMethod;

    private BigDecimal amount;

    /** 0 待支付 1 成功 2 失败 3 已关闭 */
    private Integer status;

    private LocalDateTime callbackTime;

    /** 回调原文，留痕便于对账排查 */
    private String callbackContent;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
