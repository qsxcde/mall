package com.geekmall.modules.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 资料编辑入参，对应前端个人中心「基本资料」。
 */
@Data
public class ProfileUpdateDTO implements Serializable {

    @Size(max = 32, message = "昵称过长")
    private String nickname;

    /** 0 未知 1 男 2 女 */
    private Integer gender;

    private LocalDate birthday;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(max = 255, message = "简介过长")
    private String bio;

    /** 头像 URL（由上传接口返回） */
    @Pattern(regexp = "^$|^https?://.*", message = "头像地址不合法")
    private String avatar;
}
