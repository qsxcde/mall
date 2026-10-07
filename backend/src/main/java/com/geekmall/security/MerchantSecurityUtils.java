package com.geekmall.security;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 商家端登录上下文读取工具。
 *
 * <p>核心用途是拿 {@link #getShopId()}：所有商家域查询/写操作都必须以店铺为隔离维度，
 * 避免越权访问其他店铺的数据。</p>
 */
public final class MerchantSecurityUtils {

    private MerchantSecurityUtils() {
    }

    public static MerchantLoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MerchantLoginUser merchant) {
            return merchant;
        }
        throw BizException.of(ResultCode.UNAUTHORIZED);
    }

    /** 当前商家所属店铺 ID，未登录或非商家令牌时抛 401。 */
    public static Long getShopId() {
        return getLoginUser().getShopId();
    }

    public static Long getMerchantUserId() {
        return getLoginUser().getMerchantUserId();
    }

    /**
     * 获取当前商家账号 ID，未登录或非商家令牌时返回 null。
     *
     * <p>用于「需要区分登录主体但允许匿名」的场景，例如限流按登录主体分组时
     * 先尝试买家身份、再尝试商家身份、最后回退 IP —— 这类判断不应该抛异常。</p>
     */
    public static Long getMerchantUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MerchantLoginUser merchant) {
            return merchant.getMerchantUserId();
        }
        return null;
    }
}
