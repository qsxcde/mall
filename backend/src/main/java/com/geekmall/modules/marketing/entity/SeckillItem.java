package com.geekmall.modules.marketing.entity;

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
 * 秒杀商品 mkt_seckill_item。stock 为活动库存池，与商品总库存分开计价。
 */
@Data
@TableName("mkt_seckill_item")
public class SeckillItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long productId;

    private BigDecimal seckillPrice;

    private BigDecimal oldPrice;

    /** 剩余库存 */
    private Integer stock;

    /** 总库存 */
    private Integer total;

    /** 已售 */
    private Integer sold;

    /**
     * 库存桶数：1 = 不分桶（沿用 {@link #stock} 单行扣减），&gt;= 2 启用库存分桶。
     *
     * <p>分桶后每单只扣 {@code mkt_seckill_bucket} 中的一行，{@link #stock}/{@link #sold}
     * 不再逐单更新（否则又退回单行热点），展示与对账口径改为 {@code SUM(桶)}。</p>
     */
    private Integer bucketCount;

    private String tip;

    private Integer notStart;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
