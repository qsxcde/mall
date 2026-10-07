package com.geekmall.common.ratelimit;

/**
 * 限流维度：决定「按什么把请求分组计数」。
 */
public enum RateLimitDimension {

    /**
     * 按登录主体限流：买家取 userId，商家取 merchantUserId；未登录回退到 IP。
     *
     * <p>这是绝大多数业务接口的正确维度 —— 限的是「这个人的操作频率」，
     * 而不是「这个出口 IP 的频率」，避免同一出口（公司网关 / 校园网）互相影响。</p>
     */
    SUBJECT,

    /** 按客户端 IP 限流。用于登录、验证码这类「还没有登录态」或需要防单机刷量的接口。 */
    IP,

    /** 全局维度：所有请求共享一个计数，用于保护后端整体。 */
    GLOBAL
}
