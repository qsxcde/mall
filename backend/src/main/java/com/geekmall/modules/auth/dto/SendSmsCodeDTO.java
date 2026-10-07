package com.geekmall.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

/**
 * 发送短信验证码入参。
 */
@Data
public class SendSmsCodeDTO implements Serializable {

    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 业务场景：login / register / reset */
    @NotBlank(message = "缺少场景参数")
    private String scene;
}
