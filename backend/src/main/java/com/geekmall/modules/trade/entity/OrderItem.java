package com.geekmall.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细 oms_order_item。商品信息为下单时的快照，保证历史订单不受商品变更影响。
 */
@Data
@TableName("oms_order_item")
public class OrderItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private Long productId;

    private String title;

    private String cover;

    private String spec;

    private BigDecimal price;

    private Integer qty;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
