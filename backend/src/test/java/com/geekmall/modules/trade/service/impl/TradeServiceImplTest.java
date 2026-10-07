package com.geekmall.modules.trade.service.impl;

import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.cart.service.CartService;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.dto.PreOrderDTO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.mapper.InventoryRollbackLogMapper;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import com.geekmall.modules.trade.service.OrderStateMachine;
import com.geekmall.modules.trade.vo.PreOrderVO;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交易服务单元测试（Mockito 隔离持久层与 Redis）。
 *
 * <p>覆盖结算试算的金额口径、下单前的可支付校验、取消订单的库存回退幂等，
 * 以及超时关单任务。这些逻辑一旦出错会直接造成「多退库存 / 少退库存 / 金额不符」。</p>
 */
@ExtendWith(MockitoExtension.class)
class TradeServiceImplTest {

    private static final long USER_ID = 1L;

    @Mock
    private CartService cartService;
    @Mock
    private UserService userService;
    @Mock
    private CouponService couponService;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderStatusLogMapper orderStatusLogMapper;
    @Mock
    private InventoryRollbackLogMapper rollbackLogMapper;
    @Mock
    private OrderStateMachine orderStateMachine;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ObjectProvider<TradeServiceImpl> selfProvider;

    private TradeServiceImpl tradeService;

    @BeforeEach
    void setUp() {
        tradeService = new TradeServiceImpl(cartService, userService, couponService, productMapper,
                orderMapper, orderItemMapper, orderStatusLogMapper, rollbackLogMapper,
                orderStateMachine, redisTemplate, eventPublisher, selfProvider);
    }

    private static CartItemVO cartItem(long id, long productId, String amount, boolean checked) {
        CartItemVO item = new CartItemVO();
        item.setId(id);
        item.setProductId(productId);
        item.setTitle("商品" + productId);
        item.setAmount(new BigDecimal(amount));
        item.setQty(1);
        item.setChecked(checked);
        return item;
    }

    private static AddressVO address(long id, Integer isDefault) {
        AddressVO vo = new AddressVO();
        vo.setId(id);
        vo.setName("张三");
        vo.setPhone("13800000000");
        vo.setIsDefault(isDefault);
        vo.setFullAddress("广东省深圳市南山区科技园 1 号");
        return vo;
    }

    private static Order order(long userId, int status, String orderNo) {
        Order order = new Order();
        order.setId(100L);
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStatus(status);
        order.setPayAmount(new BigDecimal("5967.00"));
        return order;
    }

    private static OrderItem orderItem(long productId, int qty) {
        OrderItem item = new OrderItem();
        item.setOrderNo("GM202610010001");
        item.setProductId(productId);
        item.setQty(qty);
        return item;
    }

    @Nested
    @DisplayName("可支付金额校验")
    class RequirePayableAmount {

        @Test
        @DisplayName("订单不存在时抛 404 业务异常")
        void shouldRejectMissingOrder() {
            when(orderMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> tradeService.requirePayableAmount(USER_ID, "GM404"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ResultCode.NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("订单不属于当前用户时按不存在处理，避免横向越权探测")
        void shouldRejectForeignOrder() {
            when(orderMapper.selectOne(any())).thenReturn(order(2L, 0, "GM_OTHER"));

            assertThatThrownBy(() -> tradeService.requirePayableAmount(USER_ID, "GM_OTHER"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ResultCode.NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("订单已支付时拒绝重复支付")
        void shouldRejectAlreadyPaidOrder() {
            when(orderMapper.selectOne(any())).thenReturn(order(USER_ID, OrderStatus.PENDING_SHIP.getCode(), "GM1"));

            assertThatThrownBy(() -> tradeService.requirePayableAmount(USER_ID, "GM1"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无需支付");
        }

        @Test
        @DisplayName("待付款订单返回应付金额，金额以服务端为准")
        void shouldReturnPayableAmount() {
            when(orderMapper.selectOne(any())).thenReturn(order(USER_ID, OrderStatus.PENDING_PAY.getCode(), "GM1"));

            assertThat(tradeService.requirePayableAmount(USER_ID, "GM1"))
                    .isEqualByComparingTo("5967.00");
        }
    }

    @Nested
    @DisplayName("结算试算")
    class PreOrder {

        @Test
        @DisplayName("无勾选商品时抛「购物车为空」")
        void shouldRejectEmptyCart() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", false)));

            assertThatThrownBy(() -> tradeService.preOrder(USER_ID, new PreOrderDTO()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("购物车为空");
        }

        @Test
        @DisplayName("默认只结算已勾选项，未勾选的不计入金额")
        void shouldOnlyIncludeCheckedItems() {
            when(cartService.list(USER_ID)).thenReturn(List.of(
                    cartItem(1L, 11L, "100.00", true),
                    cartItem(2L, 12L, "50.00", false)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());

            PreOrderVO vo = tradeService.preOrder(USER_ID, new PreOrderDTO());

            assertThat(vo.getItems()).hasSize(1);
            assertThat(vo.getGoodsAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("显式指定购物车项时按 ID 筛选，忽略勾选状态")
        void shouldFilterByExplicitIds() {
            when(cartService.list(USER_ID)).thenReturn(List.of(
                    cartItem(1L, 11L, "100.00", false),
                    cartItem(2L, 12L, "50.00", false)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());

            PreOrderDTO dto = new PreOrderDTO();
            dto.setCartItemIds(List.of(2L));
            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getItems()).hasSize(1);
            assertThat(vo.getGoodsAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("标准配送免运费，应付金额等于商品金额")
        void shouldApplyStandardShipping() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());

            PreOrderVO vo = tradeService.preOrder(USER_ID, new PreOrderDTO());

            assertThat(vo.getShippingFee()).isEqualByComparingTo("0.00");
            assertThat(vo.getPayTotal()).isEqualByComparingTo("100.00");
            assertThat(vo.getSelectedShippingType()).isEqualTo("standard");
        }

        @Test
        @DisplayName("顺丰配送加收 18 元运费")
        void shouldChargeExpressShipping() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());
            PreOrderDTO dto = new PreOrderDTO();
            dto.setShippingType("express");

            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getShippingFee()).isEqualByComparingTo("18.00");
            assertThat(vo.getPayTotal()).isEqualByComparingTo("118.00");
        }

        @Test
        @DisplayName("未知配送方式回落为标准配送，避免出现 0 元运费被绕过")
        void shouldFallbackToStandardForUnknownShipping() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());
            PreOrderDTO dto = new PreOrderDTO();
            dto.setShippingType("teleport");

            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getSelectedShippingType()).isEqualTo("standard");
            assertThat(vo.getShippingFee()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("满减券抵扣商品金额")
        void shouldApplyCouponDiscount() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());
            UserCouponVO coupon = new UserCouponVO();
            coupon.setId(5L);
            coupon.setType("full");
            when(couponService.requireUsable(eq(USER_ID), eq(5L), any(BigDecimal.class))).thenReturn(coupon);
            when(couponService.calcGoodsDiscount(eq(coupon), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal("30.00"));

            PreOrderDTO dto = new PreOrderDTO();
            dto.setCouponId(5L);
            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getDiscount()).isEqualByComparingTo("30.00");
            assertThat(vo.getPayTotal()).isEqualByComparingTo("70.00");
            assertThat(vo.getSelectedCouponId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("免运费券把运费置零")
        void shouldZeroShippingWithShippingCoupon() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());
            UserCouponVO coupon = new UserCouponVO();
            coupon.setId(5L);
            coupon.setType("shipping");
            when(couponService.requireUsable(eq(USER_ID), eq(5L), any(BigDecimal.class))).thenReturn(coupon);
            when(couponService.calcGoodsDiscount(eq(coupon), any(BigDecimal.class))).thenReturn(BigDecimal.ZERO);

            PreOrderDTO dto = new PreOrderDTO();
            dto.setCouponId(5L);
            dto.setShippingType("express");
            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getShippingFee()).isEqualByComparingTo("0.00");
            assertThat(vo.getPayTotal()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("券不可用时试算仍然成功，降级为不使用券并给出提示")
        void shouldDegradeWhenCouponUnusable() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());
            when(couponService.requireUsable(eq(USER_ID), eq(5L), any(BigDecimal.class)))
                    .thenThrow(new BizException(ResultCode.BIZ_ERROR, "该券已过期"));

            PreOrderDTO dto = new PreOrderDTO();
            dto.setCouponId(5L);
            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getSelectedCouponId()).isNull();
            assertThat(vo.getCouponNotice()).isEqualTo("该券已过期");
            assertThat(vo.getDiscount()).isEqualByComparingTo("0.00");
            assertThat(vo.getPayTotal()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("应付金额不为负：大额券不会把总额算成负数")
        void shouldClampPayTotalAtZero() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "10.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());
            UserCouponVO coupon = new UserCouponVO();
            coupon.setId(5L);
            coupon.setType("full");
            when(couponService.requireUsable(eq(USER_ID), eq(5L), any(BigDecimal.class))).thenReturn(coupon);
            when(couponService.calcGoodsDiscount(eq(coupon), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal("999.00"));

            PreOrderDTO dto = new PreOrderDTO();
            dto.setCouponId(5L);
            PreOrderVO vo = tradeService.preOrder(USER_ID, dto);

            assertThat(vo.getPayTotal()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("默认地址优先取标记为默认的地址，否则取第一条")
        void shouldResolveDefaultAddress() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID))
                    .thenReturn(List.of(address(7L, 0), address(8L, 1)));

            assertThat(tradeService.preOrder(USER_ID, new PreOrderDTO()).getDefaultAddressId()).isEqualTo(8L);
        }

        @Test
        @DisplayName("无默认地址标记时取第一条地址")
        void shouldFallbackToFirstAddress() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID))
                    .thenReturn(List.of(address(7L, 0), address(8L, 0)));

            assertThat(tradeService.preOrder(USER_ID, new PreOrderDTO()).getDefaultAddressId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("无收货地址时不设置默认地址，避免前端拿到 null 后报错")
        void shouldOmitDefaultAddressWhenEmpty() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());

            assertThat(tradeService.preOrder(USER_ID, new PreOrderDTO()).getDefaultAddressId()).isNull();
        }

        @Test
        @DisplayName("入参为 null 时也能试算（取全部已勾选项）")
        void shouldTolerateNullDto() {
            when(cartService.list(USER_ID)).thenReturn(List.of(cartItem(1L, 11L, "100.00", true)));
            when(userService.listAddresses(USER_ID)).thenReturn(List.of());

            assertThat(tradeService.preOrder(USER_ID, null).getPayTotal()).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    @DisplayName("取消订单")
    class Cancel {

        @Test
        @DisplayName("取消后回退库存并释放优惠券")
        void shouldRestoreStockAndReleaseCoupon() {
            when(orderMapper.selectOne(any())).thenReturn(order(USER_ID, OrderStatus.PENDING_PAY.getCode(), "GM202610010001"));
            when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(11L, 2), orderItem(12L, 1)));
            when(rollbackLogMapper.tryInsert(anyString(), anyLong(), anyInt())).thenReturn(1);

            tradeService.cancel(USER_ID, "GM202610010001", "不想要了");

            verify(orderStateMachine).transfer(any(Order.class), eq(OrderStatus.CANCELED),
                    eq(OrderStateMachine.OPERATOR_USER), eq("不想要了"));
            verify(productMapper).restoreStock(11L, 2);
            verify(productMapper).restoreStock(12L, 1);
            verify(couponService).releaseByOrderNo("GM202610010001");
        }

        @Test
        @DisplayName("未填写取消原因时使用默认文案")
        void shouldUseDefaultReason() {
            when(orderMapper.selectOne(any())).thenReturn(order(USER_ID, OrderStatus.PENDING_PAY.getCode(), "GM1"));
            when(orderItemMapper.selectList(any())).thenReturn(List.of());

            tradeService.cancel(USER_ID, "GM1", "   ");

            verify(orderStateMachine).transfer(any(Order.class), eq(OrderStatus.CANCELED),
                    eq(OrderStateMachine.OPERATOR_USER), eq("用户取消订单"));
        }

        @Test
        @DisplayName("回退日志已存在时跳过库存回退，保证「最多回退一次」")
        void shouldSkipAlreadyRolledBackStock() {
            when(orderMapper.selectOne(any())).thenReturn(order(USER_ID, OrderStatus.PENDING_PAY.getCode(), "GM1"));
            when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(11L, 2)));
            when(rollbackLogMapper.tryInsert(anyString(), anyLong(), anyInt())).thenReturn(0);

            tradeService.cancel(USER_ID, "GM1", null);

            verify(productMapper, never()).restoreStock(anyLong(), anyInt());
            verify(couponService).releaseByOrderNo("GM1");
        }

        @Test
        @DisplayName("订单不存在时抛 404，且不触碰库存")
        void shouldRejectMissingOrder() {
            when(orderMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> tradeService.cancel(USER_ID, "GM404", null))
                    .isInstanceOf(BizException.class);

            verify(orderStateMachine, never()).transfer(any(), any(), anyString(), anyString());
            verify(productMapper, never()).restoreStock(anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("超时关单任务")
    class CloseExpiredOrders {

        @Test
        @DisplayName("没有超时订单时返回 0，不做任何写操作")
        void shouldDoNothingWhenNoExpiredOrders() {
            when(orderMapper.selectList(any())).thenReturn(List.of());

            assertThat(tradeService.closeExpiredOrders(100)).isZero();
            verify(couponService, never()).releaseByOrderNo(anyString());
        }

        @Test
        @DisplayName("逐笔取消超时订单并回退库存与优惠券，返回关闭数量")
        void shouldCloseExpiredOrders() {
            Order first = order(USER_ID, OrderStatus.PENDING_PAY.getCode(), "GM-A");
            Order second = order(USER_ID, OrderStatus.PENDING_PAY.getCode(), "GM-B");
            when(orderMapper.selectList(any())).thenReturn(List.of(first, second));
            lenient().when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(11L, 1)));
            lenient().when(rollbackLogMapper.tryInsert(anyString(), anyLong(), anyInt())).thenReturn(1);

            int closed = tradeService.closeExpiredOrders(100);

            assertThat(closed).isEqualTo(2);
            verify(orderStateMachine, times(2)).transfer(any(Order.class), eq(OrderStatus.CANCELED),
                    eq(OrderStateMachine.OPERATOR_SYSTEM), anyString());
            verify(couponService).releaseByOrderNo("GM-A");
            verify(couponService).releaseByOrderNo("GM-B");
        }
    }
}
