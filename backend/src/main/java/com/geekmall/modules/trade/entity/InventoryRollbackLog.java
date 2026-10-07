package com.geekmall.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存回退幂等日志 inventory_rollback_log。
 *
 * <p>唯一索引 {@code (order_no, product_id)} 保证同一订单的同一商品只回退一次库存，
 * 是「取消 / 超时关闭」重复回退的最后一道防线（见 P0-4）。</p>
 */
@Data
@TableName("inventory_rollback_log")
public class InventoryRollbackLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long productId;

    private Integer qty;

    private LocalDateTime createTime;
}
