package com.geekmall.modules.cart.entity;

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
 * 购物车项 oms_cart_item。
 *
 * <p>标题 / 价格 / 封面为加入时的快照，避免商品改价后购物车显示错乱；
 * 提交订单时会以最新价格重新计算。</p>
 */
@Data
@TableName("oms_cart_item")
public class CartItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long productId;

    /** 商品标题快照 */
    private String title;

    /** 封面快照 */
    private String cover;

    /** 加入时价格快照 */
    private BigDecimal price;

    /** 规格，如「256G 钛金属」 */
    private String spec;

    private Integer qty;

    /** 是否勾选：1 是 0 否 */
    private Integer checked;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
