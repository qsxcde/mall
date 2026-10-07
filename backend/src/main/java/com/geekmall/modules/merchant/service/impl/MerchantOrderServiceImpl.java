package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.aftersale.entity.AfterSale;
import com.geekmall.modules.aftersale.mapper.AfterSaleMapper;
import com.geekmall.modules.merchant.converter.MerchantOrderConverter;
import com.geekmall.modules.merchant.dto.MerchantOrderQueryDTO;
import com.geekmall.modules.merchant.dto.OrderBatchDTO;
import com.geekmall.modules.merchant.dto.OrderNoteDTO;
import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import com.geekmall.modules.merchant.service.MerchantOrderService;
import com.geekmall.modules.merchant.support.MerchantAftersaleStatus;
import com.geekmall.modules.merchant.support.MerchantOrderStatus;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.MerchantOrderVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.service.OrderStateMachine;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商家端订单管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantOrderServiceImpl implements MerchantOrderService {

    private static final List<String> TAB_KEYS = List.of(
            "all", "wait_pay", "wait_ship", "shipped", "wait_review", "done", "closed", "after");

    private static final Map<String, java.math.BigDecimal[]> AMOUNT_RANGES = Map.of(
            "0-1000", new java.math.BigDecimal[]{java.math.BigDecimal.ZERO, new java.math.BigDecimal("1000")},
            "1000-5000", new java.math.BigDecimal[]{new java.math.BigDecimal("1000"), new java.math.BigDecimal("5000")},
            "5000-10000", new java.math.BigDecimal[]{new java.math.BigDecimal("5000"), new java.math.BigDecimal("10000")},
            "10000-999999", new java.math.BigDecimal[]{new java.math.BigDecimal("10000"), new java.math.BigDecimal("999999")});

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SysUserMapper sysUserMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final MerchantStatsMapper statsMapper;
    private final OrderStateMachine orderStateMachine;
    private final ObjectMapper objectMapper;

    @Override
    public MerchantPageVO<MerchantOrderVO> page(MerchantOrderQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getShopId, shopId);
        applyFilters(wrapper, query);

        Page<Order> page = new Page<>(query.getPage(), query.getSize());
        IPage<Order> result = orderMapper.selectPage(page, wrapper);
        List<MerchantOrderVO> list = assemble(shopId, result.getRecords());

        MerchantPageVO<MerchantOrderVO> vo = new MerchantPageVO<>(
                list, result.getTotal(), result.getCurrent(), result.getSize());
        vo.put("tabs", buildTabs(shopId));
        vo.put("stats", buildStats(shopId));
        return vo;
    }

    @Override
    public MerchantOrderVO detail(String orderNo) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
        if (order == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        return assemble(shopId, List.of(order)).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ship(OrderBatchDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Order> orders = selectOwned(shopId, dto.getIds());
        int affected = 0;
        for (Order order : orders) {
            OrderStatus current = OrderStatus.of(order.getStatus());
            if (current != OrderStatus.PENDING_SHIP) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "订单 " + order.getOrderNo() + " 当前不可发货");
            }
            // 状态机负责合法性校验、时间戳与流转日志
            orderStateMachine.transfer(order, OrderStatus.PENDING_RECEIVE,
                    OrderStateMachine.OPERATOR_MERCHANT, "商家发货");
            // 快递公司与运单号单独落库
            if (StringUtils.hasText(dto.getExpress()) || StringUtils.hasText(dto.getWaybill())) {
                Order update = new Order();
                update.setId(order.getId());
                update.setExpressCompany(dto.getExpress());
                update.setWaybillNo(dto.getWaybill());
                orderMapper.updateById(update);
            }
            affected += 1;
        }
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int close(OrderBatchDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Order> orders = selectOwned(shopId, dto.getIds());
        int affected = 0;
        for (Order order : orders) {
            String remark = StringUtils.hasText(dto.getReason()) ? dto.getReason() : "商家关闭订单";
            orderStateMachine.transfer(order, OrderStatus.CANCELED,
                    OrderStateMachine.OPERATOR_MERCHANT, remark);
            affected += 1;
        }
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveNote(OrderNoteDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .eq(Order::getOrderNo, dto.getId())
                .last("limit 1"));
        if (order == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setMerchantNote(dto.getNote());
        orderMapper.updateById(update);
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private void applyFilters(LambdaQueryWrapper<Order> wrapper, MerchantOrderQueryDTO query) {
        String status = query.getStatus();
        if (StringUtils.hasText(status) && !"all".equals(status)) {
            if (MerchantOrderStatus.AFTER.equals(status)) {
                wrapper.inSql(Order::getOrderNo, afterOrderSql());
            } else {
                Integer code = MerchantOrderStatus.toCode(status);
                if (code == null) {
                    throw new BizException(ResultCode.PARAM_ERROR, "非法的订单状态：" + status);
                }
                wrapper.eq(Order::getStatus, code);
            }
        }
        if (StringUtils.hasText(query.getPayWay()) && !"all".equals(query.getPayWay())) {
            wrapper.eq(Order::getPayMethod, query.getPayWay());
        }
        String range = query.getRange();
        if ("today".equals(range)) {
            wrapper.ge(Order::getCreateTime, LocalDate.now().atStartOfDay());
        } else if ("7".equals(range)) {
            wrapper.ge(Order::getCreateTime, LocalDateTime.now().minusDays(7));
        } else if ("30".equals(range)) {
            wrapper.ge(Order::getCreateTime, LocalDateTime.now().minusDays(30));
        }
        String amountRange = query.getAmountRange();
        if (amountRange != null && AMOUNT_RANGES.containsKey(amountRange)) {
            java.math.BigDecimal[] bounds = AMOUNT_RANGES.get(amountRange);
            wrapper.ge(Order::getPayAmount, bounds[0]).le(Order::getPayAmount, bounds[1]);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Order::getOrderNo, kw)
                    .or().apply("user_id IN (SELECT id FROM sys_user WHERE nickname LIKE CONCAT('%', {0}, '%'))", kw)
                    .or().apply("order_no IN (SELECT order_no FROM oms_order_item WHERE title LIKE CONCAT('%', {0}, '%'))", kw));
        }
        applySort(wrapper, query.getSort());
    }

    private void applySort(LambdaQueryWrapper<Order> wrapper, String sort) {
        String key = sort == null ? "" : sort;
        switch (key) {
            case "time_asc" -> wrapper.orderByAsc(Order::getCreateTime);
            case "amount_desc" -> wrapper.orderByDesc(Order::getPayAmount);
            case "amount_asc" -> wrapper.orderByAsc(Order::getPayAmount);
            default -> wrapper.orderByDesc(Order::getCreateTime);
        }
    }

    /** 组装 VO：批量取明细、买家、地址与售后中标记，避免 N+1。 */
    private List<MerchantOrderVO> assemble(Long shopId, List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = orders.stream().map(Order::getOrderNo).toList();

        Map<String, OrderItem> itemMap = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderNo, orderNos)).stream()
                .collect(Collectors.toMap(OrderItem::getOrderNo, Function.identity(), (a, b) -> a));

        Set<Long> userIds = orders.stream().map(Order::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        Set<String> afterOrderNos = afterOrderNos(shopId, orderNos);

        return orders.stream().map(order -> MerchantOrderConverter.toVO(
                order,
                itemMap.get(order.getOrderNo()),
                userMap.get(order.getUserId()),
                parseAddress(order.getAddressSnap()),
                afterOrderNos.contains(order.getOrderNo())
        )).toList();
    }

    private Set<String> afterOrderNos(Long shopId, Collection<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return Set.of();
        }
        // 用商家侧状态的统一口径判断「售后中」：只看 merchant_status 会把买家已撤销的工单误判为仍在处理中
        List<AfterSale> list = afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getShopId, shopId)
                .in(AfterSale::getOrderNo, orderNos)
                .apply(MerchantAftersaleStatus.STATUS_SQL + " IN " + MerchantAftersaleStatus.NON_FINAL_IN));
        return new HashSet<>(list.stream().map(AfterSale::getOrderNo).toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAddress(String snap) {
        if (!StringUtils.hasText(snap)) {
            return null;
        }
        try {
            return objectMapper.readValue(snap, Map.class);
        } catch (Exception e) {
            log.debug("地址快照解析失败，忽略：{}", e.getMessage());
            return null;
        }
    }

    private List<Order> selectOwned(Long shopId, List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .in(Order::getOrderNo, orderNos));
    }

    private Map<String, Object> buildTabs(Long shopId) {
        Map<String, Long> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.orderStatusCounts(shopId)) {
            byCode.merge(String.valueOf(Numbers.i(row.get("status"))), Numbers.l(row.get("cnt")), Long::sum);
        }
        Map<String, Object> tabs = new LinkedHashMap<>();
        long all = 0;
        for (Map.Entry<String, Long> entry : byCode.entrySet()) {
            all += entry.getValue();
        }
        for (String key : TAB_KEYS) {
            if ("all".equals(key)) {
                continue;
            }
            if (MerchantOrderStatus.AFTER.equals(key)) {
                tabs.put(key, statsMapper.afterOrderCount(shopId));
                continue;
            }
            Integer code = MerchantOrderStatus.toCode(key);
            tabs.put(key, code == null ? 0L : byCode.getOrDefault(String.valueOf(code), 0L));
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("all", all);
        ordered.putAll(tabs);
        return ordered;
    }

    private Map<String, Object> buildStats(Long shopId) {
        Map<String, Object> summary = statsMapper.orderSummary(shopId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayCount", Numbers.l(summary == null ? null : summary.get("todayCount")));
        stats.put("todayAmount", Numbers.bd(summary == null ? null : summary.get("todayAmount")));
        stats.put("waitShip", Numbers.l(summary == null ? null : summary.get("waitShip")));
        stats.put("shipped", Numbers.l(summary == null ? null : summary.get("shipped")));
        stats.put("after", statsMapper.afterOrderCount(shopId));
        stats.put("totalAmount", Numbers.bd(summary == null ? null : summary.get("totalAmount")));
        return stats;
    }

    private String afterOrderSql() {
        return "SELECT order_no FROM oms_aftersale WHERE shop_id = " + MerchantSecurityUtils.getShopId()
                + " AND deleted = 0 AND (merchant_status IN ('pending', 'wait_return', 'wait_receive') "
                + "OR (merchant_status IS NULL AND status = 0))";
    }
}
