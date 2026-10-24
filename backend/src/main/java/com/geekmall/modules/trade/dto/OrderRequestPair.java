package com.geekmall.modules.trade.dto;

/**
 * 下单幂等键：{@code (userId, requestId)}。
 *
 * <p>批量落库需要一次性预查多条消息是否已建单，用元组 IN 直接命中
 * {@code uk_user_request}，避免逐条 SELECT。</p>
 */
public record OrderRequestPair(Long userId, String requestId) {}
