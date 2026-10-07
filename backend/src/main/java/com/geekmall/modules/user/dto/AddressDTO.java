package com.geekmall.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 新增 / 编辑收货地址入参。
 */
@Data
public class AddressDTO implements Serializable {

    @NotBlank(message = "请输入收货人姓名")
    @Size(max = 32, message = "姓名过长")
    private String name;

    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    private String province;

    private String city;

    private String district;

    @NotBlank(message = "请输入详细地址")
    @Size(max = 255, message = "详细地址过长")
    private String detail;

    /** 是否设为默认地址 */
    private Boolean isDefault = false;
}
