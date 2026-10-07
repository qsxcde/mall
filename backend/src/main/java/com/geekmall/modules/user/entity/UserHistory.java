package com.geekmall.modules.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 浏览足迹 ums_user_history，同一商品只保留一条，重复浏览只更新时间。
 */
@Data
@TableName("ums_user_history")
public class UserHistory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long productId;

    /** 最近浏览时间 */
    private LocalDateTime viewTime;
}
