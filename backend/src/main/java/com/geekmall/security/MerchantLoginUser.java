package com.geekmall.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 商家端登录上下文对象，作为 Spring Security 的 principal。
 *
 * <p>与买家侧 {@link LoginUser} 分离：商家登录态的归属维度是「店铺」，
 * 所有 /api/v1/merchant/** 查询都必须以 shopId 为隔离条件。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantLoginUser implements Serializable {

    /** 商家账号 ID */
    private Long merchantUserId;

    private String username;

    /** 所属店铺 ID */
    private Long shopId;

    private String shopName;
}
