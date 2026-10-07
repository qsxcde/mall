package com.geekmall.common.exception;

import com.geekmall.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。抛出后由 {@link GlobalExceptionHandler} 统一转换为标准响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
    }

    public BizException(ResultCode resultCode, String msg) {
        super(msg);
        this.code = resultCode.getCode();
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public static BizException of(ResultCode resultCode) {
        return new BizException(resultCode);
    }
}
