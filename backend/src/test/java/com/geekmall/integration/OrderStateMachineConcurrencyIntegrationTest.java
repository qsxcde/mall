package com.geekmall.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import com.geekmall.modules.trade.service.OrderStateMachine;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单状态机并发集成测试。
 *
 * <p>验证的是并发正确性这一「测试才有意义」的部分：状态流转用带
 * {@code status = 读到的当前值} 的 CAS 条件更新，因此在多线程/多实例同时触发
 * （用户点击取消 + 超时任务扫描 + 支付回调）时，<b>同一个订单只能被真正流转一次</b>。</p>
 *
 * <p>这直接决定了库存回退与优惠券释放会不会重复执行 —— 重复一次就是真实的资损。</p>
 */
class OrderStateMachineConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private OrderStateMachine orderStateMachine;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderStatusLogMapper orderStatusLogMapper;

    private Order insertPendingOrder(String tag) {
        Order order = new Order();
        order.setOrderNo("GM-CONC-" + tag + "-" + System.nanoTime());
        order.setShopId(DEMO_SHOP_ID);
        order.setUserId(1L);
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setGoodsAmount(new BigDecimal("10.00"));
        order.setShippingFee(BigDecimal.ZERO);
        order.setDiscount(BigDecimal.ZERO);
        order.setPayAmount(new BigDecimal("10.00"));
        orderMapper.insert(order);
        return order;
    }

    private long statusLogCount(String orderNo) {
        return orderStatusLogMapper.selectCount(new LambdaQueryWrapper<OrderStatusLog>()
                .eq(OrderStatusLog::getOrderNo, orderNo));
    }

    /**
     * 并发执行同一目标状态的流转。
     *
     * @return 执行过程中收集到的异常（用于断言「没有非预期异常」）
     */
    private List<Throwable> transferConcurrently(Order order, OrderStatus target) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREADS);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        Order snapshot = orderMapper.selectById(order.getId());
                        orderStateMachine.transfer(snapshot, target,
                                OrderStateMachine.OPERATOR_SYSTEM, "并发测试");
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finishGate.await(30, TimeUnit.SECONDS))
                    .as("并发流转未在超时时间内完成")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        return errors;
    }

    @Test
    @DisplayName("8 个线程同时取消同一订单：只产生 1 条状态日志，库存回退只会发生一次")
    void concurrentCancelShouldTransferExactlyOnce() throws InterruptedException {
        Order order = insertPendingOrder("cancel");

        List<Throwable> errors = transferConcurrently(order, OrderStatus.CANCELED);

        assertThat(statusLogCount(order.getOrderNo()))
                .as("并发下状态日志必须只有一条，否则库存会被重复回退")
                .isEqualTo(1L);
        assertThat(orderMapper.selectById(order.getId()).getStatus())
                .isEqualTo(OrderStatus.CANCELED.getCode());
        assertThat(errors)
                .as("并发取消不应产生非预期异常")
                .isEmpty();
    }

    @Test
    @DisplayName("并发取消与发货竞争：只有一方成功，另一方被状态机拒绝")
    void concurrentConflictingTransitionsShouldHaveSingleWinner() throws InterruptedException {
        Order order = insertPendingOrder("conflict");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREADS);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < THREADS; i++) {
                // 一半线程取消，一半线程发货
                OrderStatus target = i % 2 == 0 ? OrderStatus.CANCELED : OrderStatus.PENDING_RECEIVE;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        Order snapshot = orderMapper.selectById(order.getId());
                        orderStateMachine.transfer(snapshot, target,
                                OrderStateMachine.OPERATOR_SYSTEM, "并发竞争测试");
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finishGate.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Integer finalStatus = orderMapper.selectById(order.getId()).getStatus();
        assertThat(finalStatus).isIn(OrderStatus.CANCELED.getCode(), OrderStatus.PENDING_RECEIVE.getCode());
        assertThat(statusLogCount(order.getOrderNo()))
                .as("冲突流转只能有一个赢家")
                .isEqualTo(1L);
        assertThat(errors)
                .as("失败方应收到业务异常（要求刷新），而不是系统异常")
                .allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
    }

    @Test
    @DisplayName("同一订单支付回调重复触发：只流转一次到待发货")
    void duplicatePaymentCallbackShouldTransferOnce() throws InterruptedException {
        Order order = insertPendingOrder("pay");

        List<Throwable> errors = transferConcurrently(order, OrderStatus.PENDING_SHIP);

        assertThat(statusLogCount(order.getOrderNo())).isEqualTo(1L);
        assertThat(orderMapper.selectById(order.getId()).getStatus())
                .isEqualTo(OrderStatus.PENDING_SHIP.getCode());
        assertThat(errors).isEmpty();
    }
}
