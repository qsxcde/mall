package com.geekmall.modules.payment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

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

    /** 支付渠道：local（本地模拟）/ alipay（支付宝沙箱）。 */
    private String channel;

    /** 渠道交易号（支付宝 trade_no），用于对账与主动查单；本地渠道为本地生成。 */
    private String channelTradeNo;

    /** 预下单二维码内容（支付宝 qr_code）。复用待支付单时直接返回，避免重复调网关。 */
    private String prepayQr;

    /** 退款状态：0 未退款 1 退款中 2 已退款 3 退款失败。同时是「只发起一次退款」的 CAS 闸门。 */
    private Integer refundStatus;

    /** 累计退款金额。 */
    private BigDecimal refundAmount;

    private LocalDateTime callbackTime;

    /** 回调原文，留痕便于对账排查（超长截断） */
    private String callbackContent;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
