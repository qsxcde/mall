package com.geekmall.security;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 中读取当前登录用户的工具类。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        throw BizException.of(ResultCode.UNAUTHORIZED);
    }

    public static Long getUserId() {
        return getLoginUser().getUserId();
    }

    /**
     * 获取当前登录用户 ID，未登录返回 null。
     *
     * <p>用于「匿名可访问但登录后展示个性化信息」的接口，例如领券中心标记已领取。</p>
     */
    public static Long getUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getUserId();
        }
        return null;
    }

    public static String getUsername() {
        return getLoginUser().getUsername();
    }
}
