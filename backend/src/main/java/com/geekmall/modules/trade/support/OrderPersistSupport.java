package com.geekmall.modules.trade.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.service.InventoryBucketService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.trade.converter.OrderConverter;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.mapper.InventoryRollbackLogMapper;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import com.geekmall.modules.trade.service.OrderStateMachine;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 下单落库协作类：订单主表 + 明细 + 状态日志写入、库存扣减与幂等回退、订单查询守卫。
 *
 * <p>从 {@code TradeServiceImpl} 拆出的落库块，方法体逐字搬运、行为不变；
 * 事务边界仍留在交易门面（{@code doSubmit} / {@code createOrderWithFixedPrice} /
 * {@code doCancel} / {@code cancelExpiredOne}）。本类是 trade 支撑层的最下层底座，
 * 不依赖其他协作类，被门面与各协作类共用（如 {@link #requireOwnOrder} / {@link #orderNotFound}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPersistSupport {

    /** 订单未支付自动关闭时长（分钟） */
    private static final int ORDER_EXPIRE_MINUTES = 15;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final InventoryRollbackLogMapper rollbackLogMapper;
    private final OrderNoGenerator orderNoGenerator;
    private final ProductStockService productStockService;
    /**
     * 库存分桶服务（P2-5）。
     *
     * <p>商品若已分桶，库存扣减 / 回退改走「桶级 CAS」路径；未分桶商品继续用
     * {@link ProductMapper} 的单行扣减。两条路径共用同一事务与同一业务语义。</p>
     */
    private final InventoryBucketService inventoryBucketService;

    private final UserService userService;

    /**
     * 落库订单主体 + 明细（批量）+ 初始状态日志。
     *
     * <p>购物车下单与特殊通道下单（秒杀）共用同一段写入逻辑，避免两处实现漂移。</p>
     */
    public String persistOrder(
            Long userId,
            AddressVO address,
            List<OrderItemDraft> drafts,
            BigDecimal shippingFee,
            BigDecimal discount,
            Long couponId,
            String payMethod,
            String remark,
            String requestId) {
        BigDecimal safeShipping = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        BigDecimal safeDiscount = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal goodsAmount = drafts.stream()
                .map(draft -> draft.price().multiply(BigDecimal.valueOf(draft.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal payAmount = goodsAmount
                .add(safeShipping)
                .subtract(safeDiscount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        String orderNo = orderNoGenerator.next();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setGoodsAmount(goodsAmount);
        order.setShippingFee(safeShipping);
        order.setDiscount(safeDiscount);
        order.setPayAmount(payAmount);
        order.setCouponId(couponId);
        order.setRequestId(StringUtils.hasText(requestId) ? requestId : null);
        order.setAddressSnap(OrderConverter.writeAddress(toAddressSnapshot(address)));
        order.setRemark(remark);
        order.setPayMethod(payMethod);
        order.setExpireTime(LocalDateTime.now().plusMinutes(ORDER_EXPIRE_MINUTES));
        orderMapper.insert(order);

        // P2-4：明细由循环单条 insert 改为一条多值 INSERT
        List<OrderItem> orderItems = drafts.stream()
                .map(draft -> {
                    OrderItem orderItem = new OrderItem();
                    orderItem.setOrderId(order.getId());
                    orderItem.setOrderNo(orderNo);
                    orderItem.setProductId(draft.product().getId());
                    orderItem.setTitle(draft.product().getTitle());
                    orderItem.setCover(draft.product().getCover());
                    orderItem.setSpec(draft.spec());
                    orderItem.setPrice(draft.price());
                    orderItem.setQty(draft.qty());
                    return orderItem;
                })
                .toList();
        orderItemMapper.batchInsert(orderItems);

        OrderStatusLog statusLog = new OrderStatusLog();
        statusLog.setOrderNo(orderNo);
        statusLog.setFromStatus(OrderStatus.PENDING_PAY.getCode());
        statusLog.setToStatus(OrderStatus.PENDING_PAY.getCode());
        statusLog.setOperator(OrderStateMachine.OPERATOR_USER);
        statusLog.setRemark("订单创建成功");
        orderStatusLogMapper.insert(statusLog);
        return orderNo;
    }

    /**
     * 扣减商品库存（P2-5 分桶适配）。
     *
     * <p>商品已分桶时走「桶级 CAS + 商品总库存 CAS」，未分桶时保持原有单行扣减；
     * 两条路径在库存不足时都抛 {@code OUT_OF_STOCK}，由外层事务统一回滚。</p>
     */
    public void deductProductStock(Long productId, int qty, String title, String orderNo) {
        if (inventoryBucketService.isBucketed(productId)) {
            BucketDeductDTO dto = new BucketDeductDTO();
            dto.setProductId(productId);
            dto.setQty(qty);
            dto.setOrderNo(orderNo);
            dto.setOperator("trade");
            try {
                inventoryBucketService.deduct(dto);
            } catch (BizException e) {
                if (e.getCode() == ResultCode.OUT_OF_STOCK.getCode()) {
                    throw new BizException(ResultCode.OUT_OF_STOCK, "商品「" + title + "」库存不足");
                }
                throw e;
            }
            return;
        }
        if (!productStockService.deductStock(productId, qty)) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "商品「" + title + "」库存不足");
        }
    }

    /**
     * 回退库存（P0-4 / P2-5）。
     *
     * <p>回退路径按「该单该商品是否走过桶级出库」二选一：</p>
     * <ul>
     *   <li><b>分桶商品</b>：交回 {@code InventoryBucketService}，把数量还回**原来那些桶**，
     *       幂等由桶审计流水的 {@code (单号, 商品)} 判据保证；</li>
     *   <li><b>未分桶商品</b>：先在 {@code inventory_rollback_log} 登记 {@code (orderNo, productId)}，
     *       唯一索引保证每笔回退最多生效一次。</li>
     * </ul>
     * <p>两条路径都保证「即便状态机出现意外，也不会把库存退回两次」。</p>
     */
    public void restoreStock(String orderNo) {
        List<OrderItem> items =
                orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem item : items) {
            int qty = item.getQty() == null ? 0 : item.getQty();
            // 分桶商品：交回桶服务，把数量还回「原来那些桶」，幂等由桶审计流水保证
            if (inventoryBucketService.hasOutbound(orderNo, item.getProductId())) {
                inventoryBucketService.rollbackProduct(orderNo, item.getProductId());
                continue;
            }
            int firstTime = rollbackLogMapper.tryInsert(orderNo, item.getProductId(), qty);
            if (firstTime == 0) {
                log.warn("库存回退已执行过，跳过幂等回退：orderNo={}, productId={}", orderNo, item.getProductId());
                continue;
            }
            productStockService.restoreStock(item.getProductId(), qty);
        }
    }

    private Map<String, String> toAddressSnapshot(AddressVO address) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("name", address.getName());
        snapshot.put("phone", address.getPhone());
        snapshot.put("fullAddress", address.getFullAddress() == null ? "" : address.getFullAddress());
        return snapshot;
    }

    /** 特殊通道下单（秒杀）的写库主体；事务边界在门面的 {@code createOrderWithFixedPrice} 上。 */
    public String createOrderWithFixedPrice(
            Long userId,
            Long addressId,
            Long productId,
            BigDecimal unitPrice,
            int qty,
            String remark,
            String requestId) {
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0 || qty <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单参数不合法");
        }
        AddressVO address = userService.getAddress(userId, addressId);
        Product product = productStockService.findById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != 1) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }
        List<OrderItemDraft> drafts = List.of(new OrderItemDraft(product, product.getSpec(), qty, unitPrice));
        // 与普通下单一致：先落订单，再在事务尾段扣库存
        String orderNo;
        try {
            orderNo = persistOrder(
                    userId, address, drafts, BigDecimal.ZERO, BigDecimal.ZERO, null, null, remark, requestId);
        } catch (DuplicateKeyException ex) {
            // 幂等命中：同一 (userId, requestId) 已建过单 —— 秒杀异步落库的重复消费会走到这里。
            // 唯一键冲突只影响这一条语句、不会让整个事务失效，因此可以安全地回查并返回已有订单号。
            String existing = findOrderNoByRequestId(userId, requestId);
            if (existing == null) {
                throw ex;
            }
            log.warn("特殊通道下单命中幂等：userId={}, requestId={}, 返回已有订单 {}", userId, requestId, existing);
            return existing;
        }
        deductProductStock(productId, qty, product.getTitle(), orderNo);
        log.info("用户 {} 通过特殊通道下单成功：{}，单价 ¥{}", userId, orderNo, unitPrice);
        return orderNo;
    }

    public Order requireOwnOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo).last("limit 1"));
        if (order == null || !order.getUserId().equals(userId)) {
            throw orderNotFound(orderNo);
        }
        return order;
    }

    public BizException orderNotFound(String orderNo) {
        return new BizException(ResultCode.NOT_FOUND, "订单不存在：" + orderNo);
    }

    /**
     * 按 (userId, requestId) 查已存在的订单号。
     *
     * <p>两个用途：① 唯一索引命中后的幂等返回（P1-4 兜底）；
     * ② 秒杀异步落库在扣减之前的幂等前置检查（见接口注释）。</p>
     */
    public String findOrderNoByRequestId(Long userId, String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return null;
        }
        Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getRequestId, requestId)
                .last("limit 1"));
        return existing == null ? null : existing.getOrderNo();
    }

    /** 下单明细草稿：以下单瞬间的商品数据为准。 */
    public record OrderItemDraft(Product product, String spec, int qty, BigDecimal price) {}
}
