package com.geekmall.modules.marketing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀场次 mkt_seckill_session。
 */
@Data
@TableName("mkt_seckill_session")
public class SeckillSession implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场次时间，如 14:00 */
    private String sessionTime;

    private String label;

    /** wait 未开始 / running 进行中 / done 已结束 */
    private String state;

    private Integer sort;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
