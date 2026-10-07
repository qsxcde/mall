package com.geekmall.modules.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户表 sys_user。
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（手机号） */
    private String username;

    /** BCrypt 密文，禁止出参 */
    @JsonIgnore
    private String password;

    private String phone;

    private String nickname;

    private String avatar;

    /** 1 正常 0 禁用 */
    private Integer status;

    /** 会员等级 ID */
    private Long levelId;

    /** 可用积分 */
    private Integer points;

    /** 成长值 */
    private Integer growth;

    /** 性别：0 未知 1 男 2 女 */
    private Integer gender;

    private LocalDate birthday;

    private String email;

    /** 个人简介 */
    private String bio;

    @TableLogic
    @JsonIgnore
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
