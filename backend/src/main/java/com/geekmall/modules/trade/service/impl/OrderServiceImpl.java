package com.geekmall.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.PageResult;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.trade.converter.OrderConverter;
import com.geekmall.modules.trade.dto.OrderQueryDTO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import com.geekmall.modules.trade.service.OrderService;
import com.geekmall.modules.trade.vo.LogisticsVO;
import com.geekmall.modules.trade.vo.OrderDetailVO;
import com.geekmall.modules.trade.vo.OrderStatusCountVO;
import com.geekmall.modules.trade.vo.OrderTimelineVO;
import com.geekmall.modules.trade.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单查询服务实现。
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper statusLogMapper;

    @Override
    public PageResult<OrderVO> page(Long userId, OrderQueryDTO query) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(query.getStatus() != null, Order::getStatus, query.getStatus())
                // 默认按最新下单在前
                .orderByDesc(Order::getId);

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            // P1-5：改为 EXISTS 子查询，避免旧实现「全表 LIKE 后把 ID 全量拉进 JVM + 超大 IN 列表」，
            // 关键词过短（<2）时只按订单号匹配，防止无意义的大范围扫描。
            wrapper.and(w -> {
                w.like(Order::getOrderNo, keyword);
                if (keyword.length() >= 2) {
                    w.or().apply("EXISTS (SELECT 1 FROM oms_order_item i "
                            + "WHERE i.order_no = oms_order.order_no "
                            + "AND i.title LIKE CONCAT('%', {0}, '%'))", keyword);
                }
            });
        }

        Page<Order> page = new Page<>(query.getPage(), query.getPageSize());
        IPage<Order> result = orderMapper.selectPage(page, wrapper);
        if (result.getRecords().isEmpty()) {
            return PageResult.empty(query.getPage(), query.getPageSize());
        }

        // 批量加载明细，避免 N+1 查询
        List<String> pageOrderNos = result.getRecords().stream().map(Order::getOrderNo).toList();
        Map<String, List<OrderItem>> itemMap = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderNo, pageOrderNos))
                .stream().collect(Collectors.groupingBy(OrderItem::getOrderNo));

        List<OrderVO> list = result.getRecords().stream()
                .map(order -> OrderConverter.toVO(order, itemMap.getOrDefault(order.getOrderNo(), List.of())))
                .toList();
        return new PageResult<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public OrderStatusCountVO statusCounts(Long userId) {
        // P1-6：单条 GROUP BY 替代 5 次串行 COUNT
        Map<Integer, Long> byStatus = new HashMap<>();
        long all = 0L;
        for (Map<String, Object> row : orderMapper.countGroupByStatus(userId)) {
            Integer status = row.get("status") == null ? null : ((Number) row.get("status")).intValue();
            long cnt = row.get("cnt") == null ? 0L : ((Number) row.get("cnt")).longValue();
            if (status != null) {
                byStatus.merge(status, cnt, Long::sum);
            }
            all += cnt;
        }
        OrderStatusCountVO vo = new OrderStatusCountVO();
        vo.setAll(all);
        vo.setPay(byStatus.getOrDefault(OrderStatus.PENDING_PAY.getCode(), 0L));
        vo.setShip(byStatus.getOrDefault(OrderStatus.PENDING_SHIP.getCode(), 0L));
        vo.setRecv(byStatus.getOrDefault(OrderStatus.PENDING_RECEIVE.getCode(), 0L));
        vo.setCmt(byStatus.getOrDefault(OrderStatus.PENDING_COMMENT.getCode(), 0L));
        return vo;
    }

    @Override
    public OrderDetailVO detail(Long userId, String orderNo) {
        Order order = requireOwnOrder(userId, orderNo);
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo)
                .orderByAsc(OrderItem::getId));

        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrder(OrderConverter.toVO(order, items));

        Map<String, String> snapshot = OrderConverter.readAddress(order.getAddressSnap());
        vo.setReceiverName(snapshot.get("name"));
        vo.setReceiverPhone(snapshot.get("phone"));
        vo.setReceiverAddress(snapshot.get("fullAddress"));

        vo.setPayMethod(order.getPayMethod());
        vo.setPayTime(order.getPayTime());
        vo.setTradeNo(order.getTradeNo());
        vo.setRemark(order.getRemark());
        vo.setCancelReason(order.getCancelReason());
        vo.setCancelTime(order.getCancelTime());
        vo.setExpireSecondsLeft(calcExpireSecondsLeft(order));
        vo.setTimeline(buildTimeline(order));
        vo.setLogistics(buildLogistics(order));
        return vo;
    }

    @Override
    public LogisticsVO logistics(Long userId, String orderNo) {
        Order order = requireOwnOrder(userId, orderNo);
        LogisticsVO vo = buildLogistics(order);
        return vo == null ? new LogisticsVO() : vo;
    }

    @Override
    public long countAllOrders() {
        Long count = orderMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    @Override
    public Map<String, String> firstItemTitles(Collection<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return Map.of();
        }
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderNo, orderNos)
                .orderByAsc(OrderItem::getId));
        Map<String, String> result = new LinkedHashMap<>();
        for (OrderItem item : items) {
            result.putIfAbsent(item.getOrderNo(), item.getTitle());
        }
        return result;
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private Order requireOwnOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在：" + orderNo);
        }
        return order;
    }

    private Long calcExpireSecondsLeft(Order order) {
        if (OrderStatus.of(order.getStatus()) != OrderStatus.PENDING_PAY || order.getExpireTime() == null) {
            return 0L;
        }
        long seconds = Duration.between(LocalDateTime.now(), order.getExpireTime()).getSeconds();
        return Math.max(seconds, 0L);
    }

    /**
     * 订单进度时间轴：已发生的流转取自状态日志，未发生的步骤补空节点，
     * 这样前端可以直接按 done 渲染「已完成 / 待完成」。
     */
    private List<OrderTimelineVO> buildTimeline(Order order) {
        List<OrderStatusLog> logs = statusLogMapper.selectList(new LambdaQueryWrapper<OrderStatusLog>()
                .eq(OrderStatusLog::getOrderNo, order.getOrderNo())
                .orderByAsc(OrderStatusLog::getId));
        List<OrderTimelineVO> timeline = new ArrayList<>(
                logs.stream().map(OrderConverter::toTimelineVO).toList());

        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || current == OrderStatus.CANCELED) {
            return timeline;
        }
        // 尚未到达的节点
        Map<OrderStatus, String> futureSteps = new LinkedHashMap<>();
        futureSteps.put(OrderStatus.PENDING_PAY, "提交订单");
        futureSteps.put(OrderStatus.PENDING_SHIP, "支付成功，等待发货");
        futureSteps.put(OrderStatus.PENDING_RECEIVE, "商家已发货");
        futureSteps.put(OrderStatus.PENDING_COMMENT, "确认收货");
        futureSteps.put(OrderStatus.FINISHED, "交易完成");
        futureSteps.forEach((status, text) -> {
            if (status.getCode() > current.getCode()) {
                timeline.add(new OrderTimelineVO(text, null, false));
            }
        });
        return timeline;
    }

    private LogisticsVO buildLogistics(Order order) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status == null || status.getCode() < OrderStatus.PENDING_RECEIVE.getCode()) {
            return null;
        }
        LogisticsVO vo = new LogisticsVO();
        vo.setCompany("顺丰速运");
        vo.setNo("SF" + order.getOrderNo());
        vo.setPhone("95338");

        List<OrderTimelineVO> steps = new ArrayList<>();
        LocalDateTime deliverTime = order.getDeliverTime();
        if (deliverTime != null) {
            steps.add(new OrderTimelineVO("包裹已揽收，从仓库发出", deliverTime.minusHours(2), true));
            steps.add(new OrderTimelineVO("运输中，已到达目的地城市", deliverTime, true));
        }
        if (order.getReceiveTime() != null) {
            steps.add(new OrderTimelineVO("已签收，感谢使用极客数码", order.getReceiveTime(), true));
        }
        vo.setSteps(steps);
        return vo;
    }
}
