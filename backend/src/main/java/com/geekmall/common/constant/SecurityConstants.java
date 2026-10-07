package com.geekmall.common.constant;

/**
 * 安全相关常量，包括请求头约定与免登录白名单。
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    public static final String TOKEN_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 商家端令牌的作用域声明，用于与买家端令牌区分。 */
    public static final String SCOPE_MERCHANT = "merchant";
    public static final String SCOPE_CLAIM = "scope";
    public static final String SHOP_ID_CLAIM = "shopId";

    /** 商家域权限标识。 */
    public static final String ROLE_MERCHANT = "ROLE_MERCHANT";

    /** 无需登录即可访问的路径（与前端路由的 requiresAuth 相反）。 */
    public static final String[] WHITELIST = {
            "/api/v1/auth/**",
            // 商家端登录 / 注册（其余 /api/v1/merchant/** 均需商家令牌）
            "/api/v1/merchant/auth/**",
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/home/**",
            // 秒杀只开放浏览，抢购接口需要登录（避免匿名刷库存）
            "/api/v1/seckill/sessions",
            "/api/v1/seckill/items",
            "/api/v1/coupons/templates",
            // 支付渠道异步回调不带登录态，必须在白名单内（靠签名与幂等保证安全）
            "/api/v1/pay/callback",
            "/api/v1/cms/**",
            "/actuator/**",
            // 本地磁盘存储模式下静态图片的访问路径（图片本身不含敏感信息）
            "/uploads/**",
            "/doc.html",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**",
            "/favicon.ico",
            "/error"
    };
}
