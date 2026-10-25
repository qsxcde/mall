package com.geekmall.modules.trade.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.service.InventoryBucketService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.product.service.SkuStockService;
import com.geekmall.modules.trade.converter.OrderConverter;
import com.geekmall.modules.trade.dto.FixedPriceOrderCommand;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** SKU 库存：订单明细带 SKU 时按 SKU 扣减 / 回补（含商品层联动）。 */
    private final SkuStockService skuStockService;
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
        Order order = buildOrder(
                orderNo,
                userId,
                address,
                goodsAmount,
                safeShipping,
                safeDiscount,
                payAmount,
                couponId,
                payMethod,
                remark,
                requestId);
        orderMapper.insert(order);

        // P2-4：明细由循环单条 insert 改为一条多值 INSERT
        List<OrderItem> orderItems = drafts.stream()
                .map(draft ->
                        buildOrderItem(order, draft.product(), draft.skuId(), draft.spec(), draft.qty(), draft.price()))
                .toList();
        orderItemMapper.batchInsert(orderItems);

        orderStatusLogMapper.insert(buildInitialStatusLog(orderNo));
        return orderNo;
    }

    /**
     * 批量落库秒杀订单：订单 / 明细 / 状态日志批量插入，商品库存按批归并扣减。
     *
     * <p>调用方必须已开启事务：本方法只负责「写」，事务边界留在交易门面
     * {@code createSeckillOrdersBatch} 上。语句顺序与单条下单一致 ——
     * 先完成全部插入，库存扣减放到最后，缩短商品行锁持有时间。</p>
     *
     * @return {@code requestId -> orderNo}
     */
    public Map<String, String> persistSeckillOrdersBatch(List<FixedPriceOrderCommand> commands) {
        List<FixedPriceOrderCommand> unique = dedupe(commands);
        if (unique.isEmpty()) {
            return Map.of();
        }

        Set<Long> productIds = new HashSet<>();
        for (FixedPriceOrderCommand command : unique) {
            productIds.add(command.productId());
        }
        Map<Long, Product> products = productStockService.findByIds(productIds);

        List<Order> orders = new ArrayList<>(unique.size());
        Map<String, FixedPriceOrderCommand> commandByOrderNo = new LinkedHashMap<>();
        Map<String, Product> productByOrderNo = new LinkedHashMap<>();
        Map<String, String> orderNoByRequestId = new LinkedHashMap<>();

        for (FixedPriceOrderCommand command : unique) {
            Product product = products.get(command.productId());
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                throw new BizException(ResultCode.NOT_FOUND, "商品不存在或已下架");
            }
            AddressVO address = userService.getAddress(command.userId(), command.addressId());
            BigDecimal goodsAmount = command.unitPrice()
                    .multiply(BigDecimal.valueOf(command.qty()))
                    .setScale(2, RoundingMode.HALF_UP);
            String orderNo = orderNoGenerator.next();
            Order order = buildOrder(
                    orderNo,
                    command.userId(),
                    address,
                    goodsAmount,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    goodsAmount,
                    null,
                    null,
                    command.remark(),
                    command.requestId());
            orders.add(order);
            commandByOrderNo.put(orderNo, command);
            productByOrderNo.put(orderNo, product);
            orderNoByRequestId.put(command.requestId(), orderNo);
        }

        orderMapper.batchInsert(orders);

        Map<String, Long> idByOrderNo = new LinkedHashMap<>();
        for (Order row : orderMapper.selectByOrderNos(commandByOrderNo.keySet())) {
            idByOrderNo.put(row.getOrderNo(), row.getId());
        }

        List<OrderItem> items = new ArrayList<>(orders.size());
        List<OrderStatusLog> logs = new ArrayList<>(orders.size());
        // 秒杀商品被约束为单规格，规格归属取默认 SKU（无 SKU 的历史商品为 0）
        Map<Long, Long> skuIdByProduct = new LinkedHashMap<>();
        for (Order order : orders) {
            Long id = idByOrderNo.get(order.getOrderNo());
            if (id == null) {
                throw new IllegalStateException("批量插入订单后回查 ID 失败：" + order.getOrderNo());
            }
            order.setId(id);
            FixedPriceOrderCommand command = commandByOrderNo.get(order.getOrderNo());
            Product product = productByOrderNo.get(order.getOrderNo());
            Long skuId = skuIdByProduct.computeIfAbsent(product.getId(), this::defaultSkuId);
            items.add(buildOrderItem(order, product, skuId, product.getSpec(), command.qty(), command.unitPrice()));
            logs.add(buildInitialStatusLog(order.getOrderNo()));
        }
        orderItemMapper.batchInsert(items);
        orderStatusLogMapper.batchInsert(logs);

        List<BucketDeductDTO> deductions = new ArrayList<>(orders.size());
        for (FixedPriceOrderCommand command : unique) {
            BucketDeductDTO dto = new BucketDeductDTO();
            dto.setProductId(command.productId());
            dto.setSkuId(skuIdByProduct.computeIfAbsent(command.productId(), this::defaultSkuId));
            dto.setQty(command.qty());
            dto.setOrderNo(orderNoByRequestId.get(command.requestId()));
            dto.setOperator("seckill-batch");
            dto.setRemark("秒杀批量落库");
            deductions.add(dto);
        }
        // 商品库存按商品/桶归并成一次 CAS：同一批次落在同一桶的 N 单合并扣减，
        // pms_product 总库存每批每商品只更新一次（而不是每单一次）
        inventoryBucketService.deductBatch(deductions);
        return orderNoByRequestId;
    }

    private List<FixedPriceOrderCommand> dedupe(List<FixedPriceOrderCommand> commands) {
        Map<String, FixedPriceOrderCommand> unique = new LinkedHashMap<>();
        if (commands != null) {
            for (FixedPriceOrderCommand command : commands) {
                validate(command);
                unique.putIfAbsent(command.requestId(), command);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private void validate(FixedPriceOrderCommand command) {
        if (command == null
                || !StringUtils.hasText(command.requestId())
                || command.userId() == null
                || command.addressId() == null
                || command.productId() == null
                || command.unitPrice() == null
                || command.unitPrice().signum() < 0
                || command.qty() <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单参数不合法");
        }
    }

    /** 订单主表构造（单条 / 批量共用，字段与旧实现逐项对齐）。 */
    Order buildOrder(
            String orderNo,
            Long userId,
            AddressVO address,
            BigDecimal goodsAmount,
            BigDecimal shippingFee,
            BigDecimal discount,
            BigDecimal payAmount,
            Long couponId,
            String payMethod,
            String remark,
            String requestId) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setGoodsAmount(goodsAmount);
        order.setShippingFee(shippingFee);
        order.setDiscount(discount);
        order.setPayAmount(payAmount);
        order.setCouponId(couponId);
        order.setRequestId(StringUtils.hasText(requestId) ? requestId : null);
        order.setAddressSnap(OrderConverter.writeAddress(toAddressSnapshot(address)));
        order.setRemark(remark);
        order.setPayMethod(payMethod);
        order.setExpireTime(LocalDateTime.now().plusMinutes(ORDER_EXPIRE_MINUTES));
        return order;
    }

    /** 订单明细构造（单条 / 批量共用）。{@code spec} 语义 = 该 SKU 的 spec_desc 快照。 */
    OrderItem buildOrderItem(Order order, Product product, Long skuId, String spec, int qty, BigDecimal price) {
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(order.getId());
        orderItem.setOrderNo(order.getOrderNo());
        orderItem.setProductId(product.getId());
        orderItem.setSkuId(skuId == null ? 0L : skuId);
        orderItem.setTitle(product.getTitle());
        orderItem.setCover(product.getCover());
        orderItem.setSpec(spec);
        orderItem.setPrice(price);
        orderItem.setQty(qty);
        return orderItem;
    }

    /** 订单创建时的初始状态日志（from = to = 待付款）。 */
    OrderStatusLog buildInitialStatusLog(String orderNo) {
        OrderStatusLog statusLog = new OrderStatusLog();
        statusLog.setOrderNo(orderNo);
        statusLog.setFromStatus(OrderStatus.PENDING_PAY.getCode());
        statusLog.setToStatus(OrderStatus.PENDING_PAY.getCode());
        statusLog.setOperator(OrderStateMachine.OPERATOR_USER);
        statusLog.setRemark("订单创建成功");
        return statusLog;
    }

    /**
     * 扣减库存（P2-5 分桶适配 + SKU 下沉）。
     *
     * <p>该 SKU 已分桶时走「桶级 CAS + SKU/商品两层 CAS」，未分桶时保持单行扣减
     * （{@code skuId > 0} 走 {@link SkuStockService}，无规格商品走商品级）；
     * 各路径在库存不足时都抛 {@code OUT_OF_STOCK}，由外层事务统一回滚。</p>
     */
    public void deductStock(Long skuId, Long productId, int qty, String title, String orderNo) {
        Long effectiveSkuId = skuId == null ? 0L : skuId;
        if (inventoryBucketService.isBucketed(productId, effectiveSkuId)) {
            BucketDeductDTO dto = new BucketDeductDTO();
            dto.setProductId(productId);
            dto.setSkuId(effectiveSkuId);
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
        boolean ok = effectiveSkuId > 0
                ? skuStockService.deductSkuStock(effectiveSkuId, qty)
                : productStockService.deductStock(productId, qty);
        if (!ok) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "商品「" + title + "」库存不足");
        }
    }

    /**
     * 回退库存（P0-4 / P2-5 + SKU 下沉）。
     *
     * <p>回退路径按「该单该 SKU 是否走过桶级出库」二选一：</p>
     * <ul>
     *   <li><b>分桶 SKU</b>：交回 {@code InventoryBucketService}，把数量还回**原来那些桶**，
     *       幂等由桶审计流水的 {@code (单号, SKU)} 判据保证 —— 同一订单的多个 SKU 各自回补一次；</li>
     *   <li><b>未走桶级出库的明细</b>：先在 {@code inventory_rollback_log} 登记
     *       {@code (orderNo, productId)}（唯一索引），再逐 SKU 回补。
     *       该表按商品幂等，因此同一商品的多个 SKU <b>合并成一组</b>登记一次、整组回补 ——
     *       逐条登记会让第二个 SKU 被唯一索引吞掉、库存退不回来。</li>
     * </ul>
     * <p>两条路径都保证「即便状态机出现意外，也不会把库存退回两次」。</p>
     */
    public void restoreStock(String orderNo) {
        List<OrderItem> items =
                orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo));
        Map<Long, List<OrderItem>> byProduct = new LinkedHashMap<>();
        for (OrderItem item : items) {
            Long skuId = item.getSkuId() == null ? 0L : item.getSkuId();
            // 分桶 SKU：交回桶服务，把数量还回「原来那些桶」，幂等由桶审计流水保证
            if (inventoryBucketService.hasOutbound(orderNo, skuId, item.getProductId())) {
                inventoryBucketService.rollbackSku(orderNo, skuId, item.getProductId());
                continue;
            }
            byProduct
                    .computeIfAbsent(item.getProductId(), k -> new ArrayList<>())
                    .add(item);
        }
        for (Map.Entry<Long, List<OrderItem>> entry : byProduct.entrySet()) {
            Long productId = entry.getKey();
            int total = entry.getValue().stream()
                    .mapToInt(item -> item.getQty() == null ? 0 : item.getQty())
                    .sum();
            int firstTime = rollbackLogMapper.tryInsert(orderNo, productId, total);
            if (firstTime == 0) {
                log.warn("库存回退已执行过，跳过幂等回退：orderNo={}, productId={}", orderNo, productId);
                continue;
            }
            for (OrderItem item : entry.getValue()) {
                int qty = item.getQty() == null ? 0 : item.getQty();
                Long skuId = item.getSkuId() == null ? 0L : item.getSkuId();
                if (skuId > 0) {
                    skuStockService.restoreSkuStock(skuId, qty);
                } else {
                    productStockService.restoreStock(item.getProductId(), qty);
                }
            }
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
        // 秒杀商品被约束为单规格，规格归属取默认 SKU（无 SKU 的历史商品为 0）
        Long skuId = defaultSkuId(productId);
        List<OrderItemDraft> drafts = List.of(new OrderItemDraft(product, skuId, product.getSpec(), qty, unitPrice));
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
        deductStock(skuId, productId, qty, product.getTitle(), orderNo);
        log.info("用户 {} 通过特殊通道下单成功：{}，单价 ¥{}", userId, orderNo, unitPrice);
        return orderNo;
    }

    /** 商品的默认 SKU ID（秒杀商品被约束为单规格，解析无歧义）；无 SKU 返回 0。 */
    private Long defaultSkuId(Long productId) {
        Long skuId = skuStockService.resolveSkuId(productId);
        return skuId == null ? 0L : skuId;
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

    /** 下单明细草稿：以下单瞬间的商品数据为准。{@code spec} 为该 SKU 的 spec_desc 快照。 */
    public record OrderItemDraft(Product product, Long skuId, String spec, int qty, BigDecimal price) {}
}
