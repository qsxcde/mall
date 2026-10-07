package com.geekmall.modules.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 用户资料出参，对齐前端个人中心与顶栏展示字段。
 */
@Data
@Schema(description = "用户资料")
public class UserProfileVO implements Serializable {

    private Long id;

    private String username;

    private String phone;

    private String nickname;

    private String avatar;

    @Schema(description = "性别：0 未知 1 男 2 女")
    private Integer gender;

    private LocalDate birthday;

    private String email;

    @Schema(description = "个人简介")
    private String bio;

    private Long levelId;

    @Schema(description = "可用积分")
    private Integer points;

    @Schema(description = "成长值")
    private Integer growth;
}
