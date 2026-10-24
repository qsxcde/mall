package com.geekmall.modules.trade.dto;

import java.math.BigDecimal;

/**
 * 特殊通道（秒杀）批量下单命令：单价由营销域校验后传入，不经购物车。
 *
 * <p>与单条 {@code createOrderWithFixedPrice} 的入参保持一一对应，
 * 唯一区别是带上 {@code requestId} 作为批量幂等键。</p>
 */
public record FixedPriceOrderCommand(
        String requestId, Long userId, Long addressId, Long productId, BigDecimal unitPrice, int qty, String remark) {}
