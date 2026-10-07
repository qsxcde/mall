package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.merchant.converter.MerchantFinanceConverter;
import com.geekmall.modules.merchant.dto.MerchantFinanceQueryDTO;
import com.geekmall.modules.merchant.dto.WithdrawDTO;
import com.geekmall.modules.merchant.entity.FundFlow;
import com.geekmall.modules.merchant.entity.Settlement;
import com.geekmall.modules.merchant.entity.Shop;
import com.geekmall.modules.merchant.mapper.FundFlowMapper;
import com.geekmall.modules.merchant.mapper.SettlementMapper;
import com.geekmall.modules.merchant.mapper.ShopMapper;
import com.geekmall.modules.merchant.service.MerchantFinanceService;
import com.geekmall.modules.merchant.vo.MerchantFundFlowVO;
import com.geekmall.modules.merchant.vo.MerchantPageVO;
import com.geekmall.modules.merchant.vo.MerchantSettlementVO;
import com.geekmall.modules.merchant.vo.OrderProductVO;
import com.geekmall.modules.merchant.vo.SettlementOrderVO;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.mapper.OrderItemMapper;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商家端财务结算服务实现。
 *
 * <p>口径：实结 = 成交额 - 平台佣金 - 支付服务费 - 退款扣减，
 * 佣金与服务费按 {@code mall.merchant.*-rate} 配置从成交额推导，保证任意一行可手工复核。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantFinanceServiceImpl implements MerchantFinanceService {

    private final SettlementMapper settlementMapper;
    private final FundFlowMapper fundFlowMapper;
    private final ShopMapper shopMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Value("${mall.merchant.commission-rate:0.05}")
    private BigDecimal commissionRate;

    @Value("${mall.merchant.service-rate:0.006}")
    private BigDecimal serviceRate;

    @Value("${mall.merchant.withdraw-fee-rate:0.001}")
    private BigDecimal withdrawFeeRate;

    @Value("${mall.merchant.withdraw-fee-cap:500}")
    private BigDecimal withdrawFeeCap;

    @Override
    public Map<String, Object> fundSummary() {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Settlement> settlements = settlementsDesc(shopId);

        BigDecimal pending = sum(settlements, s -> "pending".equals(s.getStatus()), Settlement::getSettle);
        BigDecimal settling = sum(settlements, s -> "settling".equals(s.getStatus()), Settlement::getSettle);
        BigDecimal settledTotal = sum(settlements, s -> "settled".equals(s.getStatus()), Settlement::getSettle);

        // 可用余额以店铺上的余额列为唯一口径（提现走条件扣减，只能与它保持一致），
        // 不再由「已结算 - 已提现」临时推导 —— 两套算法并存必然出现对不上的那天
        Shop shop = shopMapper.selectById(shopId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("balance", shop == null || shop.getBalance() == null ? BigDecimal.ZERO : shop.getBalance());
        summary.put("pending", pending);
        summary.put("settling", settling);
        // 冻结金额依赖风控 / 保证金体系，暂以 0 占位
        summary.put("frozen", BigDecimal.ZERO);
        summary.put("settledTotal", settledTotal);
        summary.put("current", settlements.isEmpty() ? null : MerchantFinanceConverter.toSettlement(settlements.get(0)));
        summary.put("feeRate", feeRate());
        return summary;
    }

    @Override
    public MerchantPageVO<MerchantSettlementVO> settlementPage(MerchantFinanceQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Settlement::getShopId, shopId);
        if (StringUtils.hasText(query.getStatus()) && !"all".equals(query.getStatus())) {
            wrapper.eq(Settlement::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(Settlement::getId);

        Page<Settlement> page = new Page<>(query.getPage(), query.getSize());
        IPage<Settlement> result = settlementMapper.selectPage(page, wrapper);
        List<MerchantSettlementVO> list = result.getRecords().stream()
                .map(MerchantFinanceConverter::toSettlement).toList();

        MerchantPageVO<MerchantSettlementVO> vo = new MerchantPageVO<>(
                list, result.getTotal(), result.getCurrent(), result.getSize());
        List<Settlement> all = settlementsDesc(shopId);
        vo.put("tabs", Map.of(
                "all", (long) all.size(),
                "settled", all.stream().filter(s -> "settled".equals(s.getStatus())).count(),
                "settling", all.stream().filter(s -> "settling".equals(s.getStatus())).count(),
                "pending", all.stream().filter(s -> "pending".equals(s.getStatus())).count()));
        vo.put("trend", buildTrend(all));
        vo.put("composition", all.isEmpty() ? List.of() : buildComposition(all.get(0)));
        vo.put("feeRate", feeRate());
        return vo;
    }

    @Override
    public MerchantPageVO<MerchantFundFlowVO> fundFlow(MerchantFinanceQueryDTO query) {
        Long shopId = MerchantSecurityUtils.getShopId();
        LambdaQueryWrapper<FundFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FundFlow::getShopId, shopId);
        if (StringUtils.hasText(query.getType()) && !"all".equals(query.getType())) {
            wrapper.eq(FundFlow::getType, query.getType());
        }
        wrapper.orderByDesc(FundFlow::getOccupyTime);

        Page<FundFlow> page = new Page<>(query.getPage(), query.getSize());
        IPage<FundFlow> result = fundFlowMapper.selectPage(page, wrapper);
        List<MerchantFundFlowVO> list = result.getRecords().stream()
                .map(MerchantFinanceConverter::toFlow).toList();
        return new MerchantPageVO<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public Map<String, Object> settleOrders(String settlementId) {
        Long shopId = MerchantSecurityUtils.getShopId();
        Settlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getShopId, shopId)
                .eq(Settlement::getSettleNo, settlementId)
                .last("limit 1"));
        if (settlement == null) {
            throw BizException.of(ResultCode.NOT_FOUND);
        }
        List<Order> orders = ordersInPeriod(shopId, settlement.getRangeLabel(), 6);
        List<SettlementOrderVO> list = buildSettleOrders(orders);

        MerchantSettlementVO settlementVO = MerchantFinanceConverter.toSettlement(settlement);
        // 账期订单笔数：明细抽屉要显示「抽样 6 / 共 N 笔」，因此按账期实际统计
        settlementVO.setOrderCount(orderMapper.selectCount(periodWrapper(shopId, settlement.getRangeLabel())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settlement", settlementVO);
        result.put("orders", list);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> withdraw(WithdrawDTO dto) {
        Long shopId = MerchantSecurityUtils.getShopId();
        BigDecimal amount = dto.getAmount().setScale(2, RoundingMode.HALF_UP);
        String requestId = dto.getRequestId().trim();

        // 幂等第一道：同一请求号已处理过，直接返回首次结果，不再扣款
        FundFlow existing = findWithdraw(shopId, requestId);
        if (existing != null) {
            return withdrawResult(existing.getAmount());
        }

        BigDecimal fee = feeOf(amount);

        // 条件扣减：「校验余额」与「扣减」由数据库一条语句原子完成。
        // 影响行数 0 表示余额不足，而不是「没找到店铺」—— 店铺必然存在（已通过鉴权）。
        int deducted = shopMapper.deductBalance(shopId, amount);
        if (deducted == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "可提现余额不足，请刷新后重试");
        }

        FundFlow flow = new FundFlow();
        flow.setShopId(shopId);
        flow.setType("withdraw");
        flow.setTitle("提现申请 ¥" + amount.subtract(fee) + "（手续费 ¥" + fee + "）");
        flow.setRequestId(requestId);
        flow.setAmount(amount);
        flow.setDirection(-1);
        flow.setOccupyTime(LocalDateTime.now());
        try {
            fundFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 幂等第二道：并发同请求号被唯一索引 uk_shop_request 拦下。
            // 此处必须抛异常回滚 —— 本次的余额扣减要撤销（另一笔已完成扣减），
            // 客户端用同一请求号重试时会命中第一道幂等检查并拿到首次结果。
            log.warn("提现请求号重复提交，已回滚本次扣减：shopId={}, requestId={}", shopId, requestId);
            throw new BizException(ResultCode.BIZ_ERROR, "该提现请求正在处理，请勿重复提交");
        }

        return withdrawResult(amount);
    }

    /**
     * 提现结果组装。
     *
     * <p>手续费是「金额 + 费率配置」的纯函数，因此幂等返回时按当前配置重算即可；
     * 落库的流水金额（amount）才是资金口径的唯一依据。</p>
     */
    private Map<String, Object> withdrawResult(BigDecimal amount) {
        BigDecimal fee = feeOf(amount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("amount", amount);
        result.put("fee", fee);
        result.put("arrival", amount.subtract(fee));
        return result;
    }

    private BigDecimal feeOf(BigDecimal amount) {
        return amount.multiply(withdrawFeeRate).min(withdrawFeeCap).setScale(2, RoundingMode.HALF_UP);
    }

    private FundFlow findWithdraw(Long shopId, String requestId) {
        return fundFlowMapper.selectOne(new LambdaQueryWrapper<FundFlow>()
                .eq(FundFlow::getShopId, shopId)
                .eq(FundFlow::getType, "withdraw")
                .eq(FundFlow::getRequestId, requestId)
                .last("limit 1"));
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private List<Settlement> settlementsDesc(Long shopId) {
        return settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getShopId, shopId)
                .orderByDesc(Settlement::getId));
    }

    private List<Map<String, Object>> buildTrend(List<Settlement> desc) {
        List<Settlement> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        return asc.stream().map(s -> {
            String label = s.getRangeLabel() == null ? "" : s.getRangeLabel().split(" ~ ")[0];
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", label);
            item.put("value", s.getSettle());
            return item;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildComposition(Settlement current) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(composition("实结到账", current.getSettle(), "green"));
        list.add(composition("平台佣金", current.getCommission(), "brand"));
        list.add(composition("支付服务费", current.getService(), "violet"));
        list.add(composition("退款扣减", current.getRefund(), "coral"));
        return list;
    }

    private Map<String, Object> composition(String name, BigDecimal value, String tone) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        item.put("tone", tone);
        return item;
    }

    private Map<String, Object> feeRate() {
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("commission", commissionRate);
        rate.put("service", serviceRate);
        return rate;
    }

    private List<SettlementOrderVO> buildSettleOrders(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = orders.stream().map(Order::getOrderNo).toList();
        Map<String, OrderItem> itemMap = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderNo, orderNos)).stream()
                .collect(Collectors.toMap(OrderItem::getOrderNo, Function.identity(), (a, b) -> a));

        return orders.stream().map(order -> {
            OrderItem item = itemMap.get(order.getOrderNo());
            BigDecimal goods = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
            SettlementOrderVO vo = new SettlementOrderVO();
            vo.setId(order.getOrderNo());
            vo.setQty(item == null ? 0 : item.getQty());
            vo.setGoods(goods);
            vo.setCommission(goods.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP));
            vo.setService(goods.multiply(serviceRate).setScale(2, RoundingMode.HALF_UP));
            vo.setSettle(goods.subtract(vo.getCommission()).subtract(vo.getService()));
            vo.setPaidAt(order.getPayTime());
            if (item != null) {
                OrderProductVO product = new OrderProductVO();
                product.setId(item.getProductId());
                product.setName(item.getTitle());
                product.setCover(item.getCover());
                product.setSpec(item.getSpec());
                product.setPrice(item.getPrice());
                vo.setProduct(product);
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /** 账期条件：起止时间从 rangeLabel「MM-DD ~ MM-DD」解析，解析失败则退化为全部有效订单。 */
    private LambdaQueryWrapper<Order> periodWrapper(Long shopId, String rangeLabel) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getShopId, shopId)
                .ne(Order::getStatus, 5);
        LocalDate[] period = parsePeriod(rangeLabel);
        if (period != null) {
            wrapper.ge(Order::getPayTime, period[0].atStartOfDay())
                    .le(Order::getPayTime, period[1].atTime(23, 59, 59));
        }
        return wrapper;
    }

    /** 取账期内的部分订单（明细抽屉抽样展示）。 */
    private List<Order> ordersInPeriod(Long shopId, String rangeLabel, int limit) {
        LambdaQueryWrapper<Order> wrapper = periodWrapper(shopId, rangeLabel)
                .orderByDesc(Order::getPayTime)
                .last("limit " + Math.max(1, limit));
        return orderMapper.selectList(wrapper);
    }

    private LocalDate[] parsePeriod(String rangeLabel) {
        if (!StringUtils.hasText(rangeLabel)) {
            return null;
        }
        String[] parts = rangeLabel.split("~");
        if (parts.length != 2) {
            return null;
        }
        try {
            int year = LocalDate.now().getYear();
            LocalDate start = parseMonthDay(parts[0].trim(), year);
            LocalDate end = parseMonthDay(parts[1].trim(), year);
            return new LocalDate[]{start, end};
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseMonthDay(String text, int year) {
        String[] md = text.split("-");
        return LocalDate.of(year, Integer.parseInt(md[0].trim()), Integer.parseInt(md[1].trim()));
    }

    private BigDecimal sum(List<Settlement> list, java.util.function.Predicate<Settlement> filter,
                           Function<Settlement, BigDecimal> getter) {
        return list.stream().filter(filter)
                .map(getter).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
