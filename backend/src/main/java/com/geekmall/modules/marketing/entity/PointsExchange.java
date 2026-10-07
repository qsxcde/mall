package com.geekmall.modules.marketing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分兑换记录 mkt_points_exchange。
 */
@Data
@TableName("mkt_points_exchange")
public class PointsExchange implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long goodsId;

    private String goodsName;

    /** 消耗积分 */
    private Integer points;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
