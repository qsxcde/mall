package com.geekmall.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.enums.PayMethod;
import com.geekmall.common.event.OrderCreatedEvent;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.cart.service.CartService;
import com.geekmall.modules.cart.vo.CartItemVO;
import com.geekmall.modules.marketing.service.CouponService;
import com.geekmall.modules.marketing.vo.UserCouponVO;
import com.geekmall.modules.inventory.dto.BucketDeductDTO;
import com.geekmall.modules.inventory.service.InventoryBucketService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.modules.trade.converter.OrderConverter;
import com.geekmall.modules.trade.dto.PreOrderDTO;
import com.geekmall.modules.trade.dto.SubmitOrderDTO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.mapper.InventoryRollbackLogMapper;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import com.geekmall.modules.trade.service.OrderStateMachine;
import com.geekmall.modules.trade.service.TradeService;
import com.geekmall.modules.trade.support.OrderNoGenerator;
import com.geekmall.modules.trade.vo.OptionVO;
import com.geekmall.modules.trade.vo.PreOrderVO;
import com.geekmall.modules.user.service.UserService;
import com.geekmall.modules.user.vo.AddressVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 交易服务实现。
 *
 * <p>下单是典型的「多写一致」场景：订单、明细、库存、优惠券必须同生共死，
 * 因此全部收敛在一个事务里，任何一步失败都整体回滚。购物车清理等非核心副作用
 * 通过事务提交后的事件异步执行，不再占用主事务与库存行锁。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeService {

    /** 订单未支付自动关闭时长（分钟） */
    private static final int ORDER_EXPIRE_MINUTES = 15;

    /**
     * 幂等占位值的<b>前缀</b>。真实值是「前缀 + 本次请求的随机令牌」。
     *
     * <p>令牌的存在是为了失败释放时能判断「这个占位还是不是我的」——
     * 只比对固定常量是不够的：占位 TTL 过期后，另一个同 requestId 的请求会写入
     * <b>同样</b>的常量值，此时裸删除仍会误删他人的占位。</p>
     */
    private static final String IDEMPOTENT_PENDING_PREFIX = "__PENDING__:";
    private static final Duration IDEMPOTENT_LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration IDEMPOTENT_RESULT_TTL = Duration.ofMinutes(30);
    /** 未传 requestId 时的指纹防重窗口（够覆盖用户手抖重复点击） */
    private static final Duration IDEMPOTENT_FP_TTL = Duration.ofSeconds(15);

    /**
     * 幂等键「比对删除」脚本（Lua，原子）。
     *
     * <p><b>为什么不能直接 {@code DEL}</b>：占位键有 5 分钟 TTL。若本次下单执行超过 TTL
     * （例如遭遇长时间锁等待），键已过期并被另一个同 requestId 的请求重新占位；
     * 此时失败清理若是裸 {@code DEL}，就会把<b>他人的占位</b>删掉 ——
     * 幂等防线被从内部拆掉，并发的重复下单会真的产生两张订单。</p>
     *
     * <p>Lua 里「GET 相等才 DEL」保证只清理自己写下的那一个占位。</p>
     */
    private static final RedisScript<Long> RELEASE_IDEMPOTENT_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /** P0-3：死锁 / 锁等待超时的最大重试次数 */
    private static final int MAX_RETRY_ON_LOCK = 3;

    private static final Map<String, BigDecimal> SHIPPING_FEES = new LinkedHashMap<>();
    private static final Map<String, String> SHIPPING_LABELS = new LinkedHashMap<>();

    static {
        SHIPPING_FEES.put("standard", new BigDecimal("0.00"));
        SHIPPING_FEES.put("express", new BigDecimal("18.00"));
        SHIPPING_FEES.put("same-day", new BigDecimal("25.00"));

        SHIPPING_LABELS.put("standard", "标准配送");
        SHIPPING_LABELS.put("express", "顺丰速运");
        SHIPPING_LABELS.put("same-day", "次日达");
    }

    private final CartService cartService;
    private final UserService userService;
    private final CouponService couponService;
    private final ProductMapper productMapper;
    /**
     * 库存分桶服务（P2-5）。
     *
     * <p>商品若已分桶，库存扣减 / 回退改走「桶级 CAS」路径；未分桶商品继续用
     * {@link ProductMapper} 的单行扣减。两条路径共用同一事务与同一业务语义。</p>
     */
    private final InventoryBucketService inventoryBucketService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final InventoryRollbackLogMapper rollbackLogMapper;
    private final OrderStateMachine orderStateMachine;
    /** 订单号生成器（Redis 号段模式，多实例安全） */
    private final OrderNoGenerator orderNoGenerator;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    /** 自引用代理：让 doSubmit 真正走 @Transactional 代理，从而支持死锁重试时重开事务 */
    private final ObjectProvider<TradeServiceImpl> selfProvider;

    /* ------------------------------ 结算试算 ------------------------------ */

    @Override
    public PreOrderVO preOrder(Long userId, PreOrderDTO dto) {
        List<CartItemVO> items = resolveCheckoutItems(userId, dto == null ? null : dto.getCartItemIds());
        if (items.isEmpty()) {
            throw BizException.of(ResultCode.CART_EMPTY);
        }
        BigDecimal goodsAmount = sumAmount(items);

        // 运费与优惠券都参与试算：用户换配送方式或换券时前端重新调用本接口，
        // 金额始终由服务端给出，避免前端自己拼算出现与下单不一致
        String shippingType = resolveShippingType(dto == null ? null : dto.getShippingType());
        BigDecimal shippingFee = resolveShippingFee(shippingType);
        BigDecimal discount = BigDecimal.ZERO;
        Long couponId = dto == null ? null : dto.getCouponId();
        String couponNotice = null;
        if (couponId != null) {
            try {
                UserCouponVO coupon = couponService.requireUsable(userId, couponId, goodsAmount);
                discount = couponService.calcGoodsDiscount(coupon, goodsAmount);
                if ("shipping".equals(coupon.getType())) {
                    shippingFee = BigDecimal.ZERO;
                }
            } catch (BizException e) {
                // 试算阶段不因券失效而整页失败：降级为不使用券并给出提示
                log.info("试算时优惠券 {} 不可用：{}", couponId, e.getMessage());
                couponId = null;
                couponNotice = e.getMessage();
            }
        }

        PreOrderVO vo = new PreOrderVO();
        vo.setItems(items);
        vo.setGoodsAmount(goodsAmount);
        vo.setShippingFee(shippingFee);
        vo.setDiscount(discount);
        vo.setPayTotal(goodsAmount.add(shippingFee).subtract(discount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        vo.setShippingOptions(buildOptions(SHIPPING_FEES, SHIPPING_LABELS));
        vo.setPaymentOptions(buildPaymentOptions());
        vo.setSelectedShippingType(shippingType);
        vo.setSelectedCouponId(couponId);
        vo.setCouponNotice(couponNotice);

        List<AddressVO> addresses = userService.listAddresses(userId);
        vo.setAddresses(addresses);
        if (!addresses.isEmpty()) {
            Long defaultId = addresses.stream()
                    .filter(a -> a.getIsDefault() != null && a.getIsDefault() == 1)
                    .map(AddressVO::getId)
                    .findFirst()
                    .orElse(addresses.get(0).getId());
            vo.setDefaultAddressId(defaultId);
        }

        vo.setCoupons(couponService.listForCheckout(userId, goodsAmount));
        return vo;
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
        String idempotentKey = buildIdempotentKey(userId, dto);
        Duration resultTtl = StringUtils.hasText(dto.getRequestId())
                ? IDEMPOTENT_RESULT_TTL : IDEMPOTENT_FP_TTL;

        String placeholder = null;
        if (idempotentKey != null) {
            String cached = redisTemplate.opsForValue().get(idempotentKey);
            if (isIdempotentResult(cached)) {
                log.info("命中下单幂等键，直接返回订单号 {}（requestId={}）", cached, dto.getRequestId());
                return cached;
            }
            // P1-4：必须用 setIfAbsent，否则并发同 requestId 会互相覆盖占位，形同没有幂等。
            // P1-6：占位值带上本次请求的随机令牌，失败释放时才能保证「只删自己的」。
            placeholder = IDEMPOTENT_PENDING_PREFIX + UUID.randomUUID();
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(idempotentKey, placeholder, IDEMPOTENT_LOCK_TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                String again = redisTemplate.opsForValue().get(idempotentKey);
                if (isIdempotentResult(again)) {
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
            releaseIdempotentKey(idempotentKey, placeholder);
            throw e;
        }
    }

    /** 幂等键里存的是「结果」（订单号），而不是占位值。 */
    private boolean isIdempotentResult(String value) {
        return value != null && !value.startsWith(IDEMPOTENT_PENDING_PREFIX);
    }

    /**
     * 释放幂等占位（P1-6）：仅当值仍是本请求写入的令牌时才删除。
     *
     * <p>用「比对删除」而不是裸 {@code DEL}，是为了避免占位 TTL 过期后
     * 误删其他请求重新写入的占位（那会让幂等失效、并发出两张订单）。</p>
     */
    private void releaseIdempotentKey(String idempotentKey, String placeholder) {
        if (idempotentKey == null || placeholder == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_IDEMPOTENT_SCRIPT, List.of(idempotentKey), placeholder);
        } catch (Exception ex) {
            // 释放失败不影响本次下单结果：占位会随 TTL 自行过期，用户稍后可重试
            log.warn("释放下单幂等键失败（将由 TTL 兜底）：key={}, error={}", idempotentKey, ex.getMessage());
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
                log.warn("命中订单唯一索引兜底，直接返回已存在订单 {}（userId={}, requestId={}）",
                        existing, userId, dto.getRequestId());
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
        List<CartItemVO> items = resolveCheckoutItems(userId, dto.getCartItemIds());
        if (items.isEmpty()) {
            throw BizException.of(ResultCode.CART_EMPTY);
        }
        AddressVO address = userService.getAddress(userId, dto.getAddressId());

        // 以商品当前价格为准重新计价；一次批量查回，消除逐条 selectById 的 N+1
        List<Long> productIds = items.stream().map(CartItemVO::getProductId).distinct().toList();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));

        List<OrderItemDraft> drafts = new ArrayList<>();
        BigDecimal goodsAmount = BigDecimal.ZERO;
        for (CartItemVO item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                throw new BizException(ResultCode.NOT_FOUND, "商品「" + item.getTitle() + "」已下架");
            }
            int qty = item.getQty() == null ? 1 : item.getQty();
            BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
            drafts.add(new OrderItemDraft(product, item.getSpec(), qty, price));
            goodsAmount = goodsAmount.add(price.multiply(BigDecimal.valueOf(qty)));
        }
        goodsAmount = goodsAmount.setScale(2, RoundingMode.HALF_UP);

        // P0-3：按 productId 升序加锁，所有事务加锁顺序一致，从根上消除跨 SKU 死锁
        drafts.sort(Comparator.comparing(d -> d.product().getId()));

        BigDecimal shippingFee = resolveShippingFee(dto.getShippingType());
        BigDecimal discount = BigDecimal.ZERO;
        UserCouponVO coupon = null;
        if (dto.getCouponId() != null) {
            coupon = couponService.requireUsable(userId, dto.getCouponId(), goodsAmount);
            discount = couponService.calcGoodsDiscount(coupon, goodsAmount);
            if ("shipping".equals(coupon.getType())) {
                shippingFee = BigDecimal.ZERO;
            }
        }
        BigDecimal payAmount = goodsAmount.add(shippingFee).subtract(discount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // 1) 先落订单主表 + 明细（批量）+ 状态日志，此时尚未持有商品行锁
        String orderNo = persistOrder(userId, address, drafts, shippingFee, discount,
                dto.getCouponId(), dto.getPayMethod(), dto.getRemark(), dto.getRequestId());

        // 2) 扣库存：同一商品可能出现在多个结算项，先按商品合并数量 —— 既避免分桶路径下
        //    「同商品重复调用命中幂等」漏扣，也减少对同一行的锁获取次数
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        Map<Long, String> titleByProduct = new LinkedHashMap<>();
        for (OrderItemDraft draft : drafts) {
            qtyByProduct.merge(draft.product().getId(), draft.qty(), Integer::sum);
            titleByProduct.putIfAbsent(draft.product().getId(), draft.product().getTitle());
        }
        // 分桶商品走桶级 CAS（多行并行），未分桶商品保持单行扣减；任一失败整体回滚
        for (Map.Entry<Long, Integer> entry : qtyByProduct.entrySet()) {
            deductProductStock(entry.getKey(), entry.getValue(), titleByProduct.get(entry.getKey()), orderNo);
        }

        // 3) 核销优惠券
        if (coupon != null) {
            couponService.markUsed(userId, coupon.getId(), orderNo);
        }

        // 4) 幂等结果在事务提交后写 Redis，避免「未提交却已留结果」
        registerIdempotentResult(idempotentKey, orderNo, resultTtl);

        // 5) 购物车清理移出主事务（AFTER_COMMIT + 异步），不再占用库存行锁；
        //    带上「下单那一刻的数量」做乐观守卫，避免删掉下单后又被加购合并进来的同一行
        Map<Long, Integer> cartItemQty = items.stream().collect(Collectors.toMap(
                CartItemVO::getId,
                item -> item.getQty() == null ? 1 : item.getQty(),
                (a, b) -> a));
        eventPublisher.publishEvent(new OrderCreatedEvent(userId, cartItemQty));

        log.info("用户 {} 下单成功：{}，应付 ¥{}", userId, orderNo, payAmount);
        return orderNo;
    }

    /* ------------------------------ 订单操作 ------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String orderNo, String reason) {
        Order order = requireOwnOrder(userId, orderNo);
        // P0-4：状态机 CAS 保证并发取消 / 取消与超时任务竞争时只有一方真正流转，
        // 从而 restoreStock 与 releaseByOrderNo 各自最多执行一次
        orderStateMachine.transfer(order, OrderStatus.CANCELED,
                OrderStateMachine.OPERATOR_USER,
                StringUtils.hasText(reason) ? reason : "用户取消订单");
        restoreStock(orderNo);
        couponService.releaseByOrderNo(orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long userId, String orderNo) {
        Order order = requireOwnOrder(userId, orderNo);
        orderStateMachine.transfer(order, OrderStatus.PENDING_COMMENT,
                OrderStateMachine.OPERATOR_USER, "确认收货");
    }

    @Override
    public void remindDelivery(Long userId, String orderNo) {
        Order order = requireOwnOrder(userId, orderNo);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.PENDING_SHIP) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前订单状态无需提醒发货");
        }
        // 真实场景这里会推送通知给商家/仓储，骨架阶段仅记录日志
        log.info("用户 {} 提醒发货，订单 {}", userId, orderNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
        if (order == null) {
            throw orderNotFound(orderNo);
        }
        orderStateMachine.transfer(order, OrderStatus.PENDING_RECEIVE,
                OrderStateMachine.OPERATOR_SYSTEM, "商家已发货");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(String orderNo, String tradeNo, String payMethod) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
        if (order == null) {
            throw orderNotFound(orderNo);
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setTradeNo(tradeNo);
        if (StringUtils.hasText(payMethod)) {
            update.setPayMethod(payMethod);
        }
        orderMapper.updateById(update);
        orderStateMachine.transfer(order, OrderStatus.PENDING_SHIP,
                OrderStateMachine.OPERATOR_PAY, "支付成功");
    }

    @Override
    public BigDecimal requirePayableAmount(Long userId, String orderNo) {
        Order order = requireOwnOrder(userId, orderNo);
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.PENDING_PAY) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "订单当前为「" + (status == null ? "未知" : status.getText()) + "」，无需支付");
        }
        return order.getPayAmount();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public String createOrderWithFixedPrice(Long userId, Long addressId, Long productId,
                                           BigDecimal unitPrice, int qty, String remark, String requestId) {
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0 || qty <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单参数不合法");
        }
        AddressVO address = userService.getAddress(userId, addressId);
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != 1) {
            throw new BizException(ResultCode.NOT_FOUND, "商品不存在或已下架");
        }
        List<OrderItemDraft> drafts = List.of(new OrderItemDraft(product, product.getSpec(), qty, unitPrice));
        // 与普通下单一致：先落订单，再在事务尾段扣库存
        String orderNo;
        try {
            orderNo = persistOrder(userId, address, drafts, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, remark, requestId);
        } catch (DuplicateKeyException ex) {
            // 幂等命中：同一 (userId, requestId) 已建过单 —— 秒杀异步落库的重复消费会走到这里。
            // 唯一键冲突只影响这一条语句、不会让整个事务失效，因此可以安全地回查并返回已有订单号。
            String existing = findOrderNoByRequestId(userId, requestId);
            if (existing == null) {
                throw ex;
            }
            log.warn("特殊通道下单命中幂等：userId={}, requestId={}, 返回已有订单 {}",
                    userId, requestId, existing);
            return existing;
        }
        deductProductStock(productId, qty, product.getTitle(), orderNo);
        log.info("用户 {} 通过特殊通道下单成功：{}，单价 ¥{}", userId, orderNo, unitPrice);
        return orderNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReviewed(Long userId, String orderNo) {
        Order order = requireOwnOrder(userId, orderNo);
        orderStateMachine.transfer(order, OrderStatus.FINISHED,
                OrderStateMachine.OPERATOR_USER, "评价完成");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        for (Order order : expired) {
            orderStateMachine.transfer(order, OrderStatus.CANCELED,
                    OrderStateMachine.OPERATOR_SYSTEM, "超时未支付，系统自动取消");
            restoreStock(order.getOrderNo());
            couponService.releaseByOrderNo(order.getOrderNo());
        }
        log.info("超时未支付订单已关闭 {} 笔", expired.size());
        return expired.size();
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /**
     * 落库订单主体 + 明细（批量）+ 初始状态日志。
     *
     * <p>购物车下单与特殊通道下单（秒杀）共用同一段写入逻辑，避免两处实现漂移。</p>
     */
    private String persistOrder(Long userId, AddressVO address, List<OrderItemDraft> drafts,
                                BigDecimal shippingFee, BigDecimal discount, Long couponId,
                                String payMethod, String remark, String requestId) {
        BigDecimal safeShipping = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        BigDecimal safeDiscount = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal goodsAmount = drafts.stream()
                .map(draft -> draft.price().multiply(BigDecimal.valueOf(draft.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal payAmount = goodsAmount.add(safeShipping).subtract(safeDiscount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

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
        List<OrderItem> orderItems = drafts.stream().map(draft -> {
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
        }).toList();
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

    /** 把幂等结果写入放到事务提交之后（失败不影响已提交订单）。 */
    private void registerIdempotentResult(String idempotentKey, String orderNo, Duration resultTtl) {
        if (idempotentKey == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        redisTemplate.opsForValue().set(idempotentKey, orderNo, resultTtl);
                    } catch (Exception e) {
                        log.error("写入下单幂等结果失败：{}", idempotentKey, e);
                    }
                }
            });
        } else {
            redisTemplate.opsForValue().set(idempotentKey, orderNo, resultTtl);
        }
    }

    private List<CartItemVO> resolveCheckoutItems(Long userId, List<Long> cartItemIds) {
        List<CartItemVO> all = cartService.list(userId);
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return all.stream().filter(i -> Boolean.TRUE.equals(i.getChecked())).toList();
        }
        Set<Long> ids = cartItemIds.stream().collect(Collectors.toSet());
        return all.stream().filter(i -> ids.contains(i.getId())).toList();
    }

    private BigDecimal sumAmount(List<CartItemVO> items) {
        return items.stream()
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveShippingType(String shippingType) {
        return (StringUtils.hasText(shippingType) && SHIPPING_FEES.containsKey(shippingType))
                ? shippingType : "standard";
    }

    private BigDecimal resolveShippingFee(String shippingType) {
        return SHIPPING_FEES.getOrDefault(resolveShippingType(shippingType), BigDecimal.ZERO);
    }

    private List<OptionVO> buildOptions(Map<String, BigDecimal> fees, Map<String, String> labels) {
        return fees.entrySet().stream()
                .map(e -> new OptionVO(e.getKey(), labels.get(e.getKey()), e.getValue(), null))
                .toList();
    }

    private List<OptionVO> buildPaymentOptions() {
        return Arrays.stream(PayMethod.values())
                .map(m -> new OptionVO(m.getCode(), m.getLabel(), null, null))
                .toList();
    }

    private Map<String, String> toAddressSnapshot(AddressVO address) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("name", address.getName());
        snapshot.put("phone", address.getPhone());
        snapshot.put("fullAddress", address.getFullAddress() == null ? "" : address.getFullAddress());
        return snapshot;
    }

    private Order requireOwnOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .last("limit 1"));
        if (order == null || !order.getUserId().equals(userId)) {
            throw orderNotFound(orderNo);
        }
        return order;
    }

    private BizException orderNotFound(String orderNo) {
        return new BizException(ResultCode.NOT_FOUND, "订单不存在：" + orderNo);
    }

    /**
     * 按 (userId, requestId) 查已存在的订单号。
     *
     * <p>两个用途：① 唯一索引命中后的幂等返回（P1-4 兜底）；
     * ② 秒杀异步落库在扣减之前的幂等前置检查（见接口注释）。</p>
     */
    @Override
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
    private void restoreStock(String orderNo) {
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem item : items) {
            int qty = item.getQty() == null ? 0 : item.getQty();
            // 分桶商品：交回桶服务，把数量还回「原来那些桶」，幂等由桶审计流水保证
            if (inventoryBucketService.hasOutbound(orderNo, item.getProductId())) {
                inventoryBucketService.rollbackProduct(orderNo, item.getProductId());
                continue;
            }
            int firstTime = rollbackLogMapper.tryInsert(orderNo, item.getProductId(), qty);
            if (firstTime == 0) {
                log.warn("库存回退已执行过，跳过幂等回退：orderNo={}, productId={}",
                        orderNo, item.getProductId());
                continue;
            }
            productMapper.restoreStock(item.getProductId(), qty);
        }
    }

    /**
     * 扣减商品库存（P2-5 分桶适配）。
     *
     * <p>商品已分桶时走「桶级 CAS + 商品总库存 CAS」，未分桶时保持原有单行扣减；
     * 两条路径在库存不足时都抛 {@code OUT_OF_STOCK}，由外层事务统一回滚。</p>
     */
    private void deductProductStock(Long productId, int qty, String title, String orderNo) {
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
        int rows = productMapper.deductStock(productId, qty);
        if (rows == 0) {
            throw new BizException(ResultCode.OUT_OF_STOCK, "商品「" + title + "」库存不足");
        }
    }

    /**
     * 构造幂等键（P1-4）。
     *
     * <p>有 requestId 时以它为准；缺失时退化为「用户 + 地址 + 结算项 + 券」指纹，
     * 配合短 TTL 覆盖用户重复点击，而不是像以前那样整段跳过幂等。</p>
     */
    private String buildIdempotentKey(Long userId, SubmitOrderDTO dto) {
        if (dto == null) {
            return null;
        }
        if (StringUtils.hasText(dto.getRequestId())) {
            return RedisKeys.ORDER_IDEMPOTENT + userId + ":" + dto.getRequestId();
        }
        String fingerprint = userId + "|" + dto.getAddressId() + "|" + dto.getCouponId() + "|"
                + (dto.getCartItemIds() == null ? "" : dto.getCartItemIds().stream()
                .sorted().map(String::valueOf).collect(Collectors.joining(",")));
        String hash = DigestUtils.md5DigestAsHex(fingerprint.getBytes(StandardCharsets.UTF_8));
        return RedisKeys.ORDER_IDEMPOTENT + "fp:" + userId + ":" + hash;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 下单明细草稿：以下单瞬间的商品数据为准。 */
    private record OrderItemDraft(Product product, String spec, int qty, BigDecimal price) {
    }
}
