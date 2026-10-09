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
 * 退款单 pay_refund_record。
 *
 * <p>支付宝退款是<b>同步模型</b>：结果直接返回在 {@code alipay.trade.refund} 的 HTTP 响应里，
 * 且支付宝不支持退款专属 notify_url（若有退款通知，仍发往支付接口配置的同一个 notify_url）。
 * 因此本表不依赖回调驱动，而是以渠道同步响应为准落状态。</p>
 *
 * <p>{@code refundNo} 同时作为支付宝的 {@code out_request_no}，让渠道侧做二次幂等。</p>
 */
@Data
@TableName("pay_refund_record")
public class PaymentRefundRecord implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台退款单号（= 支付宝 out_request_no），唯一索引保证同一退款单只落一次。 */
    private String refundNo;

    /** 关联支付单号 pay_payment_record.trade_no。 */
    private String tradeNo;

    private String orderNo;

    private Long userId;

    /** 渠道：local / alipay。 */
    private String channel;

    /** 渠道退款单号。 */
    private String channelRefundNo;

    private BigDecimal amount;

    /** 0 处理中 1 已退款 2 退款失败 */
    private Integer status;

    private String reason;

    private LocalDateTime callbackTime;

    /** 渠道响应原文（留痕） */
    private String callbackContent;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
