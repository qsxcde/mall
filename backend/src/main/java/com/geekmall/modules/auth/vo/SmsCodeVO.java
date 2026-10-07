package com.geekmall.modules.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 发送验证码结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "发送验证码结果")
public class SmsCodeVO implements Serializable {

    @Schema(description = "是否已发送")
    private Boolean sent;

    @Schema(description = "可再次发送的冷却秒数")
    private Integer cooldownSeconds;

    @Schema(description = "开发环境回显的验证码，生产为 null")
    private String devCode;
}
