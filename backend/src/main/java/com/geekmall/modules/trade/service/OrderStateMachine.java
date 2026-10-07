package com.geekmall.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.event.OrderStatusChangedEvent;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 订单状态机。
 *
 * <p>所有状态变更必须经此入口：校验 {@link OrderStatus#canTransferTo} 合法性、
 * 写入状态流转日志、附带对应的时间戳。这样「谁能改状态、能改成什么」只有一处实现，
 * 避免支付回调、超时任务、用户操作各写一套导致状态错乱。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateMachine {

    public static final String OPERATOR_USER = "user";
    public static final String OPERATOR_SYSTEM = "system";
    public static final String OPERATOR_PAY = "payment";
    /** 商家后台操作（发货 / 关闭订单）。 */
    public static final String OPERATOR_MERCHANT = "merchant";

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 流转订单状态。
     *
     * @param order    当前订单（要求 status 为最新值）
     * @param target   目标状态
     * @param operator 操作方：user / system / payment
     * @param remark   备注，会写入状态日志并在详情页时间轴展示
     * @return 更新后的订单（status 已同步）
     */
    @Transactional(rollbackFor = Exception.class)
    public Order transfer(Order order, OrderStatus target, String operator, String remark) {
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "订单状态异常：" + order.getStatus());
        }
        if (current == target) {
            // 幂等：重复回调 / 重复点击不报错，直接返回
            log.debug("订单 {} 已是目标状态 {}，跳过流转", order.getOrderNo(), target.getText());
            return order;
        }
        if (!current.canTransferTo(target)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "订单当前为「" + current.getText() + "」，不允许变更为「" + target.getText() + "」");
        }

        Order update = new Order();
        update.setId(order.getId());
        update.setStatus(target.getCode());
        applyTimestamp(update, target, remark);

        // P0-4：带 status = 读到的 current 作为 CAS 条件，保证同一订单的状态流转
        // 在并发/重复触发下只有一个线程能成功，从根上杜绝「重复回退库存 / 重复核销」。
        int rows = orderMapper.update(update, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, current.getCode()));
        if (rows == 0) {
            Order latest = orderMapper.selectById(order.getId());
            if (latest != null && Objects.equals(latest.getStatus(), target.getCode())) {
                // 已被其他线程流转到同一目标状态：按幂等处理，直接返回
                log.info("订单 {} 状态已被并发流转到 {}，本次按幂等返回", order.getOrderNo(), target.getText());
                order.setStatus(target.getCode());
                return order;
            }
            throw new BizException(ResultCode.BIZ_ERROR, "订单状态已被其他操作变更，请刷新后重试");
        }

        OrderStatusLog statusLog = new OrderStatusLog();
        statusLog.setOrderNo(order.getOrderNo());
        statusLog.setFromStatus(current.getCode());
        statusLog.setToStatus(target.getCode());
        statusLog.setOperator(operator);
        statusLog.setRemark(remark);
        statusLogMapper.insert(statusLog);

        order.setStatus(target.getCode());

        // 状态变更事件：站内消息/推送等副作用由监听器在事务提交后处理，
        // 交易域不关心有多少个下游，也不会因为下游失败而回滚订单
        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getUserId(), order.getOrderNo(), current.getCode(), target.getCode(), remark));

        log.info("订单 {} 状态流转：{} → {}（{}）", order.getOrderNo(), current.getText(), target.getText(), operator);
        return order;
    }

    private void applyTimestamp(Order update, OrderStatus target, String remark) {
        LocalDateTime now = LocalDateTime.now();
        switch (target) {
            case PENDING_SHIP -> update.setPayTime(now);
            case PENDING_RECEIVE -> update.setDeliverTime(now);
            case PENDING_COMMENT -> update.setReceiveTime(now);
            case FINISHED -> update.setFinishTime(now);
            case CANCELED -> {
                update.setCancelTime(now);
                update.setCancelReason(remark);
            }
            default -> {
                // PENDING_PAY 为订单初始状态，无需额外时间戳
            }
        }
    }
}
