package com.geekmall.modules.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 已读回执 sys_user_message_read：按「用户 × 消息」记录，唯一索引保证幂等。
 */
@Data
@TableName("sys_user_message_read")
public class MessageRead implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long messageId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
