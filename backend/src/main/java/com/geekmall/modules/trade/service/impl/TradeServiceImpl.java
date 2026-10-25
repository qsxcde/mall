package com.geekmall.modules.trade.service.impl;

import static com.geekmall.modules.trade.support.OrderIdempotentSupport.IDEMPOTENT_FP_TTL;
import static com.geekmall.modules.trade.support.OrderIdempotentSupport.IDEMPOTENT_LOCK_TTL;
import static com.geekmall.modules.trade.support.OrderIdempotentSupport.IDEMPOTENT_PENDING_PREFIX;
import static com.geekmall.modules.trade.support.OrderIdempotentSupport.IDEMPOTENT_RESULT_TTL;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.event.OrderCreatedEvent;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import com.geekmall.modules.payment.service.PaymentRefundService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.entity.Sku;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.product.service.SkuStockService;
import com.geekmall.modules.trade.dto.FixedPriceOrderCommand;
import com.geekmall.modules.trade.dto.OrderRequestPair;
import com.geekmall.modules.trade.dto.PreOrderDTO;
import com.geekmall.modules.trade.dto.SubmitOrderDTO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.service.OrderStateMachine;
import com.geekmall.modules.trade.service.TradeService;
import com.geekmall.modules.trade.support.OrderCheckoutSupport;
import com.geekmall.modules.trade.support.OrderIdempotentSupport;
import com.geekmall.modules.trade.support.OrderPersistSupport;
import com.geekmall.modules.trade.support.OrderPersistSupport.OrderItemDraft;
import com.geekmall.modules.trade.vo.PreOrderVO;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 交易服务实现。
 *
 * <p>下单是典型的「多写一致」场景：订单、明细、库存、优惠券必须同生共死，
 * 因此全部收敛在一个事务里，任何一步失败都整体回滚。购物车清理等非核心副作用
 * 通过事务提交后的事件异步执行，不再占用主事务与库存行锁。</p>
 *
 * <p>实现按职责拆给 {@code support} 下的协作类（事务边界与自代理调用统一在本门面）：
 * {@link OrderCheckoutSupport} 结算试算、{@link OrderPersistSupport} 落库与库存、
 * {@link OrderIdempotentSupport} 下单幂等；门面保留全部接口方法与下单/支付/关单编排。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeService {

    /** P0-3：死锁 / 锁等待超时的最大重试次数 */
    private static final int MAX_RETRY_ON_LOCK = 3;

    private final UserService userService;
    private final CouponService couponService;
    private final ProductStockService productStockService;
    /** SKU 读取与库存：下单按 SKU 重新计价，扣减下沉到 SKU。 */
    private final SkuStockService skuStockService;

    private final OrderMapper orderMapper;
    private final OrderStateMachine orderStateMachine;

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    /** 自引用代理：让 doSubmit 真正走 @Transactional 代理，从而支持死锁重试时重开事务 */
    private final ObjectProvider<TradeServiceImpl> selfProvider;
    /** 退款编排：取消 / 关闭一笔「已支付」订单后触发渠道退款。 */
    private final PaymentRefundService paymentRefundService;

    private final OrderCheckoutSupport checkoutSupport;
    private final OrderPersistSupport persistSupport;
    private final OrderIdempotentSupport idempotentSupport;

    /* ------------------------------ 结算试算 ------------------------------ */

    @Override
    public PreOrderVO preOrder(Long userId, PreOrderDTO dto) {
        return checkoutSupport.preOrder(userId, dto);
    }

    /* ------------------------------ 下单 ------------------------------ */

    /**
     * 提交订单。
     *
     * <p>本方法<b>刻意不加事务</b>：它负责幂等占位与死锁重试，真正的写库在
     * {@link #doSubmit} 里。这样每次重试都会开启一个全新事务，
     * 避免「事务已标记回滚」导致重试无效。</p>
     */
    @Override
    public String submit(Long userId, SubmitOrderDTO dto) {
        String idempotentKey = idempotentSupport.buildIdempotentKey(userId, dto);
        Duration resultTtl = StringUtils.hasText(dto.getRequestId()) ? IDEMPOTENT_RESULT_TTL : IDEMPOTENT_FP_TTL;

        String placeholder = null;
        if (idempotentKey != null) {
            String cached = redisTemplate.opsForValue().get(idempotentKey);
            if (idempotentSupport.isIdempotentResult(cached)) {
                log.info("命中下单幂等键，直接返回订单号 {}（requestId={}）", cached, dto.getRequestId());
                return cached;
            }
            // P1-4：必须用 setIfAbsent，否则并发同 requestId 会互相覆盖占位，形同没有幂等。
            // P1-6：占位值带上本次请求的随机令牌，失败释放时才能保证「只删自己的」。
            placeholder = IDEMPOTENT_PENDING_PREFIX + UUID.randomUUID();
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(idempotentKey, placeholder, IDEMPOTENT_LOCK_TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                String again = redisTemplate.opsForValue().get(idempotentKey);
                if (idempotentSupport.isIdempotentResult(again)) {
                    return again;
                }
                throw new BizException(ResultCode.BIZ_ERROR, "订单正在处理中，请勿重复提交");
            }
        }

        try {
            return submitWithRetry(userId, dto, idempotentKey, resultTtl);
        } catch (RuntimeException e) {
            // 下单失败要释放幂等键，否则用户重试会被自己的占位锁住。
            // 用 Lua 比对令牌删除：只清自己写的那一个，绝不误删他人的重新占位（P1-6）
            idempotentSupport.releaseIdempotentKey(idempotentKey, placeholder);
            throw e;
        }
    }

    /**
     * 死锁 / 锁等待超时重试（P0-3 / 任务 24）。
     *
     * <p>库存已按 productId 排序加锁，正常情况下不会死锁；这里作为兜底，
     * 遇到 InnoDB 死锁（40001）或锁等待超时退避重试，避免直接给用户报错。</p>
     */
    private String submitWithRetry(Long userId, SubmitOrderDTO dto, String idempotentKey, Duration resultTtl) {
        int attempt = 0;
        while (true) {
            try {
                return selfProvider.getObject().doSubmit(userId, dto, idempotentKey, resultTtl);
            } catch (DuplicateKeyException e) {
                // P1-4 兜底：唯一索引 uk_user_request 命中，说明同 (user_id, request_id) 的订单已存在
                // （典型场景：Redis 幂等键已过期/被清理，但订单早已落库）。此时应幂等返回已有订单，
                // 而不是把数据库约束冲突暴露成 500/9999。
                String existing = findOrderNoByRequestId(userId, dto.getRequestId());
                if (existing == null) {
                    throw e;
                }
                log.warn("命中订单唯一索引兜底，直接返回已存在订单 {}（userId={}, requestId={}）", existing, userId, dto.getRequestId());
                if (idempotentKey != null) {
                    redisTemplate.opsForValue().set(idempotentKey, existing, resultTtl);
                }
                return existing;
            } catch (ConcurrencyFailureException e) {
                if (++attempt >= MAX_RETRY_ON_LOCK) {
                    log.error("下单连续遭遇死锁/锁等待 {} 次，放弃重试：userId={}", attempt, userId, e);
                    throw e;
                }
                long backoff = 30L * attempt;
                log.warn("下单遭遇死锁/锁冲突，第 {} 次退避重试（{}ms）：userId={}", attempt, backoff, userId);
                sleepQuietly(backoff);
            }
        }
    }

    /**
     * 真正写库：订单 + 明细 + 状态日志 + 库存扣减 + 优惠券核销。
     *
     * <p>语句顺序经过刻意编排（P0-2）：先完成所有读与插入，把<b>库存扣减放到事务末尾</b>，
     * 使商品行锁的持有时间从「整个事务」缩短到「最后几条 SQL」。</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public String doSubmit(Long userId, SubmitOrderDTO dto, String idempotentKey, Duration resultTtl) {
        List<CartItemVO> items = checkoutSupport.resolveCheckoutItems(userId, dto.getCartItemIds());
        if (items.isEmpty()) {
            throw BizException.of(ResultCode.CART_EMPTY);
        }
        AddressVO address = userService.getAddress(userId, dto.getAddressId());

        // 以商品 / SKU 当前价格为准重新计价；一次批量查回，消除逐条 selectById 的 N+1
        List<Long> productIds =
                items.stream().map(CartItemVO::getProductId).distinct().toList();
        Map<Long, Product> productMap = productStockService.findByIds(productIds);
        List<Long> skuIds = items.stream()
                .map(CartItemVO::getSkuId)
                .filter(skuId -> skuId != null && skuId > 0)
                .distinct()
                .toList();
        Map<Long, Sku> skuMap = skuIds.isEmpty() ? Map.of() : skuStockService.findByIds(skuIds);

        List<OrderItemDraft> drafts = new ArrayList<>();
        BigDecimal goodsAmount = BigDecimal.ZERO;
        for (CartItemVO item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                throw new BizException(ResultCode.NOT_FOUND, "商品「" + item.getTitle() + "」已下架");
            }
            int qty = item.getQty() == null ? 1 : item.getQty();
            Sku sku = resolveSku(item, product, skuMap);
            // 价格以 SKU 为准（购物车里的只是加购快照）；spec 语义 = 该 SKU 的 spec_desc 快照
            BigDecimal price;
            String spec;
            Long skuId;
            if (sku != null) {
                price = sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice();
                spec = sku.getSpecDesc();
                skuId = sku.getId();
            } else {
                price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
                spec = item.getSpec();
                skuId = item.getSkuId() == null ? 0L : item.getSkuId();
            }
            drafts.add(new OrderItemDraft(product, skuId, spec, qty, price));
            goodsAmount = goodsAmount.add(price.multiply(BigDecimal.valueOf(qty)));
        }
        goodsAmount = goodsAmount.setScale(2, RoundingMode.HALF_UP);

        // P0-3：按 (商品, SKU) 升序加锁，所有事务加锁顺序一致，从根上消除跨 SKU 死锁
        drafts.sort(Comparator.comparing((OrderItemDraft d) -> d.product().getId())
                .thenComparing(d -> d.skuId() == null ? 0L : d.skuId()));

        BigDecimal shippingFee = checkoutSupport.resolveShippingFee(dto.getShippingType());
        BigDecimal discount = BigDecimal.ZERO;
        UserCouponVO coupon = null;
        if (dto.getCouponId() != null) {
            coupon = couponService.requireUsable(userId, dto.getCouponId(), goodsAmount);
            discount = couponService.calcGoodsDiscount(coupon, goodsAmount);
            if ("shipping".equals(coupon.getType())) {
                shippingFee = BigDecimal.ZERO;
            }
        }
        BigDecimal payAmount = goodsAmount
                .add(shippingFee)
                .subtract(discount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        // 1) 先落订单主表 + 明细（批量）+ 状态日志，此时尚未持有商品行锁
        String orderNo = persistSupport.persistOrder(
                userId,
                address,
                drafts,
                shippingFee,
                discount,
                dto.getCouponId(),
                dto.getPayMethod(),
                dto.getRemark(),
                dto.getRequestId());

        // 2) 扣库存：同一 SKU 可能出现在多个结算项，先按 (商品, SKU) 合并数量 —— 既避免分桶路径下
        //    「同 SKU 重复调用命中幂等」漏扣，也减少对同一行的锁获取次数
        Map<StockKey, Integer> qtyByKey = new LinkedHashMap<>();
        Map<StockKey, String> titleByKey = new LinkedHashMap<>();
        for (OrderItemDraft draft : drafts) {
            StockKey key = new StockKey(draft.product().getId(), draft.skuId() == null ? 0L : draft.skuId());
            qtyByKey.merge(key, draft.qty(), Integer::sum);
            titleByKey.putIfAbsent(key, draft.product().getTitle());
        }
        // 分桶 SKU 走桶级 CAS（多行并行），未分桶保持单行扣减；任一失败整体回滚
        for (Map.Entry<StockKey, Integer> entry : qtyByKey.entrySet()) {
            StockKey key = entry.getKey();
            persistSupport.deductStock(key.skuId(), key.productId(), entry.getValue(), titleByKey.get(key), orderNo);
        }

        // 3) 核销优惠券
        if (coupon != null) {
            couponService.markUsed(userId, coupon.getId(), orderNo);
        }

        // 4) 幂等结果在事务提交后写 Redis，避免「未提交却已留结果」
        idempotentSupport.registerIdempotentResult(idempotentKey, orderNo, resultTtl);

        // 5) 购物车清理移出主事务（AFTER_COMMIT + 异步），不再占用库存行锁；
        //    带上「下单那一刻的数量」做乐观守卫，避免删掉下单后又被加购合并进来的同一行
        Map<Long, Integer> cartItemQty = items.stream()
                .collect(Collectors.toMap(
                        CartItemVO::getId, item -> item.getQty() == null ? 1 : item.getQty(), (a, b) -> a));
        eventPublisher.publishEvent(new OrderCreatedEvent(userId, cartItemQty));

        log.info("用户 {} 下单成功：{}，应付 ¥{}", userId, orderNo, payAmount);
        return orderNo;
    }

    /* ------------------------------ 订单操作 ------------------------------ */

    @Override
    public void cancel(Long userId, String orderNo, String reason) {
        // 拆成两步：取消是本地事务，退款要调渠道（HTTP）——
        // 把渠道调用放进取消事务里会长时间持有订单行锁。
        selfProvider.getObject().doCancel(userId, orderNo, reason);
        // 已支付订单被取消 → 转退款；未支付订单在 refundIfPaid 内部直接 no-op
        paymentRefundService.refundIfPaid(orderNo, "订单取消退款");
    }

    @Transactional(rollbackFor = Exception.class)
    public void doCancel(Long userId, String orderNo, String reason) {
        Order order = persistSupport.requireOwnOrder(userId, orderNo);
        // P0-4：状态机 CAS 保证并发取消 / 取消与超时任务竞争时只有一方真正流转，
        // 从而 restoreStock 与 releaseByOrderNo 各自最多执行一次
        orderStateMachine.transfer(
                order,
                OrderStatus.CANCELED,
                OrderStateMachine.OPERATOR_USER,
                StringUtils.hasText(reason) ? reason : "用户取消订单");
        persistSupport.restoreStock(orderNo);
        couponService.releaseByOrderNo(orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long userId, String orderNo) {
        Order order = persistSupport.requireOwnOrder(userId, orderNo);
        orderStateMachine.transfer(order, OrderStatus.PENDING_COMMENT, OrderStateMachine.OPERATOR_USER, "确认收货");
    }

    @Override
    public void remindDelivery(Long userId, String orderNo) {
        Order order = persistSupport.requireOwnOrder(userId, orderNo);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.PENDING_SHIP) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前订单状态无需提醒发货");
        }
        // 真实场景这里会推送通知给商家/仓储，骨架阶段仅记录日志
        log.info("用户 {} 提醒发货，订单 {}", userId, orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo).last("limit 1"));
        if (order == null) {
            throw persistSupport.orderNotFound(orderNo);
        }
        orderStateMachine.transfer(order, OrderStatus.PENDING_RECEIVE, OrderStateMachine.OPERATOR_SYSTEM, "商家已发货");
    }

    @Override
    public PaymentApplyResult applyPayment(String orderNo, String tradeNo, String payMethod) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo).last("limit 1"));
        if (order == null) {
            throw persistSupport.orderNotFound(orderNo);
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "订单状态异常：" + order.getStatus());
        }
        if (current == OrderStatus.CANCELED) {
            log.warn("订单 {} 已关闭，支付成功需转退款", orderNo);
            return PaymentApplyResult.ORDER_CLOSED;
        }
        // 订单已不在待支付：同一 tradeNo 视为幂等，不同 tradeNo 视为第二笔支付，必须退款
        if (current != OrderStatus.PENDING_PAY) {
            return idempotentSupport.resolveAlreadyPaid(order, tradeNo);
        }
        try {
            // 走代理开启独立事务：tradeNo 写入与状态流转要么一起成功、要么一起回滚
            selfProvider.getObject().doApplyPayment(order, tradeNo, payMethod);
            return PaymentApplyResult.APPLIED;
        } catch (BizException e) {
            // 状态在「读到」与「流转」之间被并发改动：回读最新状态再判定，
            // 不能把异常抛给支付域（那会回滚掉支付单已成功）。
            Order latest = orderMapper.selectById(order.getId());
            if (latest != null) {
                OrderStatus latestStatus = OrderStatus.of(latest.getStatus());
                if (latestStatus == OrderStatus.CANCELED) {
                    log.warn("订单 {} 在支付落账过程中被并发关闭，转退款", orderNo);
                    return PaymentApplyResult.ORDER_CLOSED;
                }
                if (latestStatus != OrderStatus.PENDING_PAY) {
                    return idempotentSupport.resolveAlreadyPaid(latest, tradeNo);
                }
            }
            throw e;
        }
    }

    /** 支付落账的事务单元，仅供 {@link #applyPayment} 经代理调用。 */
    @Transactional(rollbackFor = Exception.class)
    public void doApplyPayment(Order order, String tradeNo, String payMethod) {
        Order update = new Order();
        update.setId(order.getId());
        update.setTradeNo(tradeNo);
        if (StringUtils.hasText(payMethod)) {
            update.setPayMethod(payMethod);
        }
        orderMapper.updateById(update);
        orderStateMachine.transfer(order, OrderStatus.PENDING_SHIP, OrderStateMachine.OPERATOR_PAY, "支付成功");
    }

    @Override
    public BigDecimal requirePayableAmount(Long userId, String orderNo) {
        Order order = persistSupport.requireOwnOrder(userId, orderNo);
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.PENDING_PAY) {
            throw new BizException(
                    ResultCode.BIZ_ERROR, "订单当前为「" + (status == null ? "未知" : status.getText()) + "」，无需支付");
        }
        return order.getPayAmount();
    }

    @Override
    public long remainingPaySeconds(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo).last("limit 1"));
        if (order == null || order.getExpireTime() == null) {
            return 0L;
        }
        return Math.max(
                0L, Duration.between(LocalDateTime.now(), order.getExpireTime()).getSeconds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public String createOrderWithFixedPrice(
            Long userId,
            Long addressId,
            Long productId,
            BigDecimal unitPrice,
            int qty,
            String remark,
            String requestId) {
        return persistSupport.createOrderWithFixedPrice(
                userId, addressId, productId, unitPrice, qty, remark, requestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReviewed(Long userId, String orderNo) {
        Order order = persistSupport.requireOwnOrder(userId, orderNo);
        orderStateMachine.transfer(order, OrderStatus.FINISHED, OrderStateMachine.OPERATOR_USER, "评价完成");
    }

    @Override
    public int closeExpiredOrders(int batchSize) {
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .isNotNull(Order::getExpireTime)
                .lt(Order::getExpireTime, LocalDateTime.now())
                .orderByAsc(Order::getId)
                .last("limit " + Math.max(1, batchSize)));
        if (expired.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Order order : expired) {
            try {
                // 逐单独立事务：某笔订单在「扫描到」与「关闭」之间被并发支付时，
                // 只跳过这一笔 —— 否则异常会向上冒泡，把整批（含其它已关闭订单的
                // 库存回补与优惠券释放）一起回滚掉（支付闭环上线后这个窗口会被真实触发）
                selfProvider.getObject().cancelExpiredOne(order);
                closed++;
            } catch (Exception e) {
                log.warn("订单 {} 超时关闭失败（可能已被并发支付），跳过本轮", order.getOrderNo(), e);
                continue;
            }
            // 关单事务已提交：若此前「支付单已成功、订单落账却失败」，此时订单会被误关。
            // 放在事务外调用渠道退款，避免 HTTP 调用持有订单行锁；失败留给对账/人工兜底。
            try {
                paymentRefundService.refundIfPaid(order.getOrderNo(), "订单超时关闭，支付成功转退款");
            } catch (Exception e) {
                log.error("订单 {} 超时关闭后退款失败，需人工核查", order.getOrderNo(), e);
            }
        }
        log.info("超时未支付订单已关闭 {} 笔（本批扫描 {} 笔）", closed, expired.size());
        return closed;
    }

    /** 单笔超时关闭的事务单元，仅供 {@link #closeExpiredOrders} 经代理调用。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelExpiredOne(Order order) {
        orderStateMachine.transfer(order, OrderStatus.CANCELED, OrderStateMachine.OPERATOR_SYSTEM, "超时未支付，系统自动取消");
        persistSupport.restoreStock(order.getOrderNo());
        couponService.releaseByOrderNo(order.getOrderNo());
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /**
     * 按 (userId, requestId) 查已存在的订单号。
     *
     * <p>两个用途：① 唯一索引命中后的幂等返回（P1-4 兜底）；
     * ② 秒杀异步落库在扣减之前的幂等前置检查（见接口注释）。</p>
     */
    @Override
    public String findOrderNoByRequestId(Long userId, String requestId) {
        return persistSupport.findOrderNoByRequestId(userId, requestId);
    }

    @Override
    public Map<String, String> findExistingSeckillOrders(List<OrderRequestPair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Order order : orderMapper.selectByUserRequestPairs(pairs)) {
            if (order.getRequestId() != null) {
                result.put(order.getRequestId(), order.getOrderNo());
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 15)
    public Map<String, String> createSeckillOrdersBatch(List<FixedPriceOrderCommand> commands) {
        return persistSupport.persistSeckillOrdersBatch(commands);
    }

    /**
     * 取购物车行对应的 SKU：无规格（{@code skuId} 为空或 0）返回 {@code null}；
     * SKU 不存在或不属于该商品视为规格已失效（可能被商家重建过），拒单让用户重新选择。
     */
    private Sku resolveSku(CartItemVO item, Product product, Map<Long, Sku> skuMap) {
        Long skuId = item.getSkuId();
        if (skuId == null || skuId <= 0) {
            return null;
        }
        Sku sku = skuMap.get(skuId);
        if (sku == null || !product.getId().equals(sku.getProductId())) {
            throw new BizException(ResultCode.NOT_FOUND, "商品「" + item.getTitle() + "」的规格已失效，请重新选择");
        }
        return sku;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 扣减合并键：同一 (商品, SKU) 的数量先合并，避免重复扣减与多余行锁。 */
    private record StockKey(Long productId, Long skuId) {}
}
