package com.geekmall.modules.message.entity;

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
 * 站内消息 sys_message。
 *
 * <p>userId = 0 表示系统广播，所有用户可见；已读状态另外存
 * {@link MessageRead}，避免广播消息「一人已读、全员已读」。</p>
 */
@Data
@TableName("sys_message")
public class Message implements Serializable {

    public static final long BROADCAST_USER_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** order / logistics / coupon / system */
    private String type;

    private String title;

    private String description;

    /** 点击跳转的前端路由 */
    private String link;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
