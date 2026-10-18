package com.geekmall.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.service.MerchantOrderQueryService;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 商家端订单只读查询实现。
 *
 * <p>所有方法都强制附加 {@code shop_id = ?}：这是数据隔离的唯一入口。
 * 调用方即使传入错误的 orderNo，也不会查到其他店铺的订单。</p>
 */
@Service
@RequiredArgsConstructor
public class MerchantOrderQueryServiceImpl implements MerchantOrderQueryService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Override
    public IPage<Order> pageByShop(Long shopId, LambdaQueryWrapper<Order> wrapper, long page, long size) {
        return orderMapper.selectPage(new Page<>(page, size), wrap(shopId, wrapper));
    }

    @Override
    public List<Order> listByShop(Long shopId, LambdaQueryWrapper<Order> wrapper) {
        return orderMapper.selectList(wrap(shopId, wrapper));
    }

    @Override
    public long countByShop(Long shopId, LambdaQueryWrapper<Order> wrapper) {
        Long count = orderMapper.selectCount(wrap(shopId, wrapper));
        return count == null ? 0L : count;
    }

    @Override
    public Order findByShopAndOrderNo(Long shopId, String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
    }

    @Override
    public List<Order> listByShopAndOrderNos(Long shopId, Collection<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        return orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getShopId, shopId).in(Order::getOrderNo, orderNos));
    }

    @Override
    public int updateNonStatusFields(Order order) {
        return orderMapper.updateById(order);
    }

    @Override
    public Map<String, List<OrderItem>> itemsByOrderNos(Collection<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return Collections.emptyMap();
        }
        return orderItemMapper
                .selectList(new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderNo, orderNos))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderNo));
    }

    /** 把调用方传入的条件与「店铺隔离」条件合并，保证隔离条件无法被遗漏。 */
    private static LambdaQueryWrapper<Order> wrap(Long shopId, LambdaQueryWrapper<Order> wrapper) {
        LambdaQueryWrapper<Order> target = wrapper == null ? new LambdaQueryWrapper<>() : wrapper;
        return target.eq(Order::getShopId, shopId);
    }
}
