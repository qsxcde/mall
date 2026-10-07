package com.geekmall.modules.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户优惠券 mkt_user_coupon。
 *
 * <p>唯一索引 (user_id, template_id) 使「重复领取」天然幂等。</p>
 */
@Data
@TableName("mkt_user_coupon")
public class UserCoupon implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long templateId;

    /** 0 未使用 1 已使用 2 已过期 */
    private Integer status;

    /** 核销订单号，取消订单时据此回退 */
    private String orderNo;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;
}
