package com.geekmall.modules.message.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内消息出参，对应前端 MessagesView。
 */
@Data
@Schema(description = "站内消息")
public class MessageVO implements Serializable {

    private Long id;

    @Schema(description = "order / logistics / coupon / system")
    private String type;

    private String title;

    private String desc;

    private String link;

    @Schema(description = "当前用户是否已读")
    private Boolean read;

    private LocalDateTime time;
}
