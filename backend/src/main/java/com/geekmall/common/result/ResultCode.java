package com.geekmall.common.result;

import lombok.Getter;

/**
 * 统一业务错误码。
 *
 * <p>约定：0 表示成功；4xx 对应客户端问题；5xx 对应服务端问题。</p>
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "success"),
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "系统繁忙，请稍后再试"),

    /* ---------- 业务错误码 ---------- */
    BIZ_ERROR(1000, "业务处理失败"),
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_ALREADY_EXISTS(1002, "账号或手机号已被注册"),
    PASSWORD_ERROR(1003, "账号或密码错误"),
    SMS_CODE_ERROR(1004, "验证码错误或已过期"),
    ACCOUNT_DISABLED(1005, "账号已被禁用"),
    OUT_OF_STOCK(2001, "商品库存不足"),
    CART_EMPTY(3001, "购物车为空"),

    SYSTEM_ERROR(9999, "系统繁忙，请稍后重试");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
