package com.geekmall.modules.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日签到记录表 ums_user_sign，唯一索引 (user_id, sign_date) 保证一天一条。
 */
@Data
@TableName("ums_user_sign")
public class UserSign implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate signDate;

    /** 本次签到获得的积分 */
    private Integer points;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
