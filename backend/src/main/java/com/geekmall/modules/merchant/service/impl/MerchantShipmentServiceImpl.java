package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.merchant.dto.MerchantShipmentQueryDTO;
import com.geekmall.modules.merchant.dto.OrderBatchDTO;
import com.geekmall.modules.merchant.service.MerchantShipmentService;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.BuyerVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantShipmentVO;
import com.geekmall.modules.merchant.vo.OrderProductVO;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商家端发货中心服务实现。
 *
 * <p>发货单以订单为数据源：订单进入「待发货」即产生发货单，
 * 「打单」落地为写入运单号，确认发货复用订单状态机做状态流转。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantShipmentServiceImpl implements MerchantShipmentService {

    /** 发货时效承诺：付款后 24 小时内发货。 */
    private static final int SHIP_LIMIT_HOURS = 24;
    /** 临期阈值：距时限 ≤ 6 小时视为紧急。 */
    private static final int LATE_HOURS = 6;

    private static final List<String> EXPRESS_LIST =
            List.of("顺丰速运", "京东物流", "中通快递", "圆通速递", "德邦快递");
    private static final List<String> WAREHOUSE_LIST = List.of("深圳总仓", "杭州仓", "北京仓");

    /** 平台默认承运商：商家未指定时用于打单 */
    private static final String DEFAULT_EXPRESS = "顺丰速运";

    private static final String STATUS_WAIT = "wait";
    private static final String STATUS_PRINTED = "printed";
    private static final String STATUS_SHIPPED = "shipped";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SysUserMapper sysUserMapper;
    private final OrderStateMachine orderStateMachine;
    private final ObjectMapper objectMapper;

    @Override
    public MerchantPageVO<MerchantShipmentVO> page(MerchantShipmentQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<Order> wrapper = baseWrapper(shopId);
        applyStatusFilter(wrapper, query.getStatus());
        if (StringUtils.hasText(query.getExpress()) && !"all".equals(query.getExpress())) {
            wrapper.eq(Order::getExpressCompany, query.getExpress());
        }
        if (StringUtils.hasText(query.getWarehouse()) && !"all".equals(query.getWarehouse())) {
            wrapper.eq(Order::getWarehouse, query.getWarehouse());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Order::getOrderNo, kw)
                    .or().like(Order::getWaybillNo, kw)
                    .or().apply("user_id IN (SELECT id FROM sys_user WHERE nickname LIKE CONCAT('%', {0}, '%'))", kw)
                    .or().apply("order_no IN (SELECT order_no FROM oms_order_item WHERE title LIKE CONCAT('%', {0}, '%'))", kw));
        }
        applySort(wrapper, query.getSort());

        Page<Order> page = new Page<>(query.getPage(), query.getSize());
        IPage<Order> result = orderMapper.selectPage(page, wrapper);
        List<MerchantShipmentVO> list = assemble(result.getRecords());

        MerchantPageVO<MerchantShipmentVO> vo = new MerchantPageVO<>(
                list, result.getTotal(), result.getCurrent(), result.getSize());
        List<Order> all = orderMapper.selectList(baseWrapper(shopId));
        vo.put("tabs", buildTabs(all));
        vo.put("stats", buildStats(all));
        return vo;
    }

    @Override
    public Map<String, Object> filters() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("express", EXPRESS_LIST);
        map.put("warehouse", WAREHOUSE_LIST);
        return map;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int print(List<String> ids) {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Order> orders = selectOwned(shopId, ids);
        int affected = 0;
        for (Order order : orders) {
            if (OrderStatus.of(order.getStatus()) != OrderStatus.PENDING_SHIP) {
                continue;
            }
            Order update = new Order();
            update.setId(order.getId());
            if (!StringUtils.hasText(order.getWaybillNo())) {
                update.setWaybillNo("SF" + (1000000000L + order.getId() * 99991));
            }
            // 打单即确定承运商：未指定时落到平台默认承运商，面单才有承运商可印
            if (!StringUtils.hasText(order.getExpressCompany())) {
                update.setExpressCompany(DEFAULT_EXPRESS);
            }
            affected += orderMapper.updateById(update);
        }
        return affected;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deliver(OrderBatchDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Order> orders = selectOwned(shopId, dto.getIds());
        int affected = 0;
        for (Order order : orders) {
            if (OrderStatus.of(order.getStatus()) != OrderStatus.PENDING_SHIP) {
                throw new BizException(ResultCode.BIZ_ERROR, "订单 " + order.getOrderNo() + " 当前不可发货");
            }
            if (!StringUtils.hasText(order.getWaybillNo())) {
                Order withWaybill = new Order();
                withWaybill.setId(order.getId());
                withWaybill.setWaybillNo("SF" + (1000000000L + order.getId() * 99991));
                orderMapper.updateById(withWaybill);
            }
            orderStateMachine.transfer(order, OrderStatus.PENDING_RECEIVE,
                    OrderStateMachine.OPERATOR_MERCHANT, "商家确认发货");
            affected += 1;
        }
        return affected;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /** 发货单来源：已付款且未取消的订单。 */
    private LambdaQueryWrapper<Order> baseWrapper(Long shopId) {
        return new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .in(Order::getStatus,
                        List.of(OrderStatus.PENDING_SHIP.getCode(), OrderStatus.PENDING_RECEIVE.getCode(),
                                OrderStatus.PENDING_COMMENT.getCode(), OrderStatus.FINISHED.getCode()));
    }

    private void applyStatusFilter(LambdaQueryWrapper<Order> wrapper, String status) {
        if (!StringUtils.hasText(status) || "all".equals(status)) {
            return;
        }
        switch (status) {
            case "late" -> wrapper.eq(Order::getStatus, OrderStatus.PENDING_SHIP.getCode())
                    .le(Order::getPayTime, LocalDateTime.now().minusHours(SHIP_LIMIT_HOURS - LATE_HOURS));
            case "printed" -> wrapper.eq(Order::getStatus, OrderStatus.PENDING_SHIP.getCode())
                    .isNotNull(Order::getWaybillNo).ne(Order::getWaybillNo, "");
            case "shipped" -> wrapper.in(Order::getStatus,
                    List.of(OrderStatus.PENDING_RECEIVE.getCode(), OrderStatus.PENDING_COMMENT.getCode(),
                            OrderStatus.FINISHED.getCode()));
            case "exc" -> wrapper.eq(Order::getId, -1L);
            default -> {
                // 未知状态等同于「全部」
            }
        }
    }

    private void applySort(LambdaQueryWrapper<Order> wrapper, String sort) {
        String key = sort == null ? "" : sort;
        switch (key) {
            case "time_asc" -> wrapper.orderByAsc(Order::getPayTime);
            case "time_desc" -> wrapper.orderByDesc(Order::getPayTime);
            default -> wrapper.orderByAsc(Order::getStatus).orderByAsc(Order::getPayTime);
        }
    }

    private List<MerchantShipmentVO> assemble(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = orders.stream().map(Order::getOrderNo).toList();
        Map<String, OrderItem> itemMap = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderNo, orderNos)).stream()
                .collect(Collectors.toMap(OrderItem::getOrderNo, Function.identity(), (a, b) -> a));
        Set<Long> userIds = orders.stream().map(Order::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        return orders.stream().map(order -> toVO(order, itemMap.get(order.getOrderNo()), userMap.get(order.getUserId()))).toList();
    }

    private MerchantShipmentVO toVO(Order order, OrderItem item, SysUser buyer) {
        MerchantShipmentVO vo = new MerchantShipmentVO();
        vo.setId(order.getOrderNo());
        vo.setStatus(shipmentStatus(order));
        vo.setQty(item == null ? 0 : item.getQty());
        vo.setAmount(order.getPayAmount());
        vo.setExpress(order.getExpressCompany());
        vo.setWaybill(order.getWaybillNo());
        vo.setWarehouse(order.getWarehouse());
        vo.setPaidAt(order.getPayTime());
        vo.setPrintedAt(StringUtils.hasText(order.getWaybillNo()) ? order.getUpdateTime() : null);
        vo.setShippedAt(order.getDeliverTime());

        LocalDateTime deadline = order.getPayTime() == null ? null
                : order.getPayTime().plusHours(SHIP_LIMIT_HOURS);
        vo.setDeadline(deadline);
        vo.setHoursLeft(STATUS_WAIT.equals(vo.getStatus()) && deadline != null
                ? Numbers.round(Duration.between(LocalDateTime.now(), deadline).toMinutes() / 60.0, 2)
                : null);

        if (item != null) {
            OrderProductVO product = new OrderProductVO();
            product.setId(item.getProductId());
            product.setName(item.getTitle());
            product.setCover(item.getCover());
            product.setSpec(item.getSpec());
            product.setPrice(item.getPrice());
            vo.setProduct(product);
        }
        if (buyer != null) {
            BuyerVO buyerVO = new BuyerVO();
            buyerVO.setName(buyer.getNickname() == null ? buyer.getUsername() : buyer.getNickname());
            buyerVO.setLevel(buyer.getLevelId() == null ? null : "V" + buyer.getLevelId());
            vo.setBuyer(buyerVO);
            vo.setPhone(buyer.getPhone());
        }
        applyAddress(vo, order.getAddressSnap());
        return vo;
    }

    @SuppressWarnings("unchecked")
    private void applyAddress(MerchantShipmentVO vo, String snap) {
        if (!StringUtils.hasText(snap)) {
            return;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(snap, Map.class);
            String province = map.get("province") == null ? "" : String.valueOf(map.get("province"));
            String detail = map.get("detail") == null ? "" : String.valueOf(map.get("detail"));
            vo.setProvince(province);
            vo.setArea((province + " " + detail).trim());
        } catch (Exception e) {
            log.debug("发货单地址快照解析失败：{}", e.getMessage());
        }
    }

    /** 发货单状态由订单状态 + 运单号派生。 */
    private String shipmentStatus(Order order) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status == OrderStatus.PENDING_SHIP) {
            return StringUtils.hasText(order.getWaybillNo()) ? STATUS_PRINTED : STATUS_WAIT;
        }
        return STATUS_SHIPPED;
    }

    private Map<String, Object> buildTabs(List<Order> all) {
        long wait = all.stream().filter(o -> STATUS_WAIT.equals(shipmentStatus(o))).count();
        long printed = all.stream().filter(o -> STATUS_PRINTED.equals(shipmentStatus(o))).count();
        long shipped = all.stream().filter(o -> STATUS_SHIPPED.equals(shipmentStatus(o))).count();
        long late = all.stream().filter(this::isLate).count();
        Map<String, Object> tabs = new LinkedHashMap<>();
        tabs.put("all", (long) all.size());
        tabs.put("late", late);
        tabs.put("printed", printed);
        tabs.put("shipped", shipped);
        tabs.put("exc", 0L);
        return tabs;
    }

    private Map<String, Object> buildStats(List<Order> all) {
        long wait = all.stream().filter(o -> STATUS_WAIT.equals(shipmentStatus(o))).count();
        long printed = all.stream().filter(o -> STATUS_PRINTED.equals(shipmentStatus(o))).count();
        long shipped = all.stream().filter(o -> STATUS_SHIPPED.equals(shipmentStatus(o))).count();
        long late = all.stream().filter(this::isLate).count();

        LocalDateTime from7d = LocalDateTime.now().minusDays(7);
        List<Order> shipped7d = all.stream()
                .filter(o -> o.getDeliverTime() != null && o.getDeliverTime().isAfter(from7d))
                .toList();
        long late7d = shipped7d.stream()
                .filter(o -> o.getPayTime() != null && o.getDeliverTime().isAfter(o.getPayTime().plusHours(SHIP_LIMIT_HOURS)))
                .count();
        double onTimeRate = shipped7d.isEmpty() ? 1d
                : Numbers.divide(shipped7d.size() - late7d, shipped7d.size());

        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        long printedToday = all.stream()
                .filter(o -> StringUtils.hasText(o.getWaybillNo()) && o.getUpdateTime() != null
                        && o.getUpdateTime().isAfter(todayStart))
                .count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("wait", wait);
        stats.put("printed", printed);
        stats.put("shipped", shipped);
        stats.put("exc", 0L);
        stats.put("late", late);
        stats.put("onTimeRate", Numbers.round(onTimeRate, 4));
        stats.put("shipped7d", (long) shipped7d.size());
        stats.put("late7d", late7d);
        stats.put("printedToday", printedToday);
        return stats;
    }

    /** 是否临期：待发货且距时限 ≤ 6 小时。 */
    private boolean isLate(Order order) {
        if (!STATUS_WAIT.equals(shipmentStatus(order)) || order.getPayTime() == null) {
            return false;
        }
        LocalDateTime deadline = order.getPayTime().plusHours(SHIP_LIMIT_HOURS);
        return Duration.between(LocalDateTime.now(), deadline).toHours() <= LATE_HOURS;
    }

    private List<Order> selectOwned(Long shopId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .in(Order::getOrderNo, ids));
    }
}
