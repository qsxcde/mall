package com.geekmall.modules.trade.entity;

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
 * 订单主表 oms_order。
 */
@Data
@TableName("oms_order")
public class Order implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺（商家端数据隔离维度） */
    private Long shopId;

    private String orderNo;

    private Long userId;

    /** 见 {@link com.geekmall.common.enums.OrderStatus} */
    private Integer status;

    private BigDecimal goodsAmount;

    private BigDecimal shippingFee;

    private BigDecimal discount;

    private BigDecimal payAmount;

    /** 使用的用户优惠券 ID */
    private Long couponId;

    /** 客户端幂等键（配合唯一索引 uk_user_request 兜底防重复下单） */
    private String requestId;

    /** 下单时的收货地址快照（JSON） */
    private String addressSnap;

    private String remark;

    /** 商家备注（仅商家端可见） */
    private String merchantNote;

    private String payMethod;

    private LocalDateTime payTime;

    private String tradeNo;

    /** 快递公司 */
    private String expressCompany;

    /** 运单号 */
    private String waybillNo;

    /** 发货仓库 */
    private String warehouse;

    private LocalDateTime deliverTime;

    private LocalDateTime receiveTime;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private String cancelReason;

    /** 超时未支付自动取消的时间点 */
    private LocalDateTime expireTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
