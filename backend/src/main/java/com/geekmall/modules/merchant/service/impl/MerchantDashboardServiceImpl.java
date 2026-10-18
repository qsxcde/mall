package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.merchant.entity.Settlement;
import com.geekmall.modules.merchant.entity.Shop;
import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import com.geekmall.modules.merchant.mapper.SettlementMapper;
import com.geekmall.modules.merchant.mapper.ShopMapper;
import com.geekmall.modules.merchant.service.MerchantDashboardService;
import com.geekmall.modules.merchant.support.MerchantAnalyticsAssembler;
import com.geekmall.modules.merchant.support.MerchantDailySeries;
import com.geekmall.modules.merchant.support.MerchantDailySeries.DayPoint;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.NavBadgesVO;
import com.geekmall.security.MerchantSecurityUtils;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 经营概览 / 数据看板服务实现。
 *
 * <p>口径说明：成交额、订单数均取自 oms_order（含未取消订单）；
 * 「访客数 / 流量来源 / 地域」等依赖前端埋点与地域库，当前后端尚无数据源，
 * 对应字段返回空集合或 0，待埋点接入后在 {@code traffic/regions} 处补齐。</p>
 */
@Service
@RequiredArgsConstructor
public class MerchantDashboardServiceImpl implements MerchantDashboardService {

    /** 发货临期阈值（小时），与前端「≤6 小时」预警保持一致 */
    private static final int SHIP_LATE_HOURS = 18;
    /** 月度目标缺省值（店铺未配置目标时兜底） */
    private static final BigDecimal DEFAULT_MONTH_TARGET = new BigDecimal("5000000");

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("M/d");

    private final MerchantStatsMapper statsMapper;
    private final ShopMapper shopMapper;
    private final SettlementMapper settlementMapper;
    /** 日序列计算（与看板共用同一套补齐/环比口径）。 */
    private final MerchantDailySeries dailySeries;
    /** 数据看板组装器（analytics 页面独立成类，见其类注释）。 */
    private final MerchantAnalyticsAssembler analyticsAssembler;

    @Override
    public NavBadgesVO navBadges() {
        Long shopId = MerchantSecurityUtils.getShopId();
        NavBadgesVO vo = new NavBadgesVO();
        Map<String, Object> product = statsMapper.productSummary(shopId);
        vo.setProductWarn(Numbers.l(product == null ? null : product.get("warn")));
        vo.setOrderPending(statsMapper.waitShipCount(shopId));
        vo.setShippingLate(statsMapper.shippingLateCount(shopId, SHIP_LATE_HOURS));
        vo.setAftersale(statsMapper.aftersalePendingCount(shopId));
        vo.setMarketing(statsMapper.runningPromotionCount(shopId));
        vo.setReviewWait(statsMapper.reviewWaitCount(shopId));
        return vo;
    }

    @Override
    public Map<String, Object> overview() {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<DayPoint> points = dailySeries.series(shopId, 60);

        List<DayPoint> last7 = dailySeries.tail(points, 7);
        List<DayPoint> prev7 = dailySeries.range(points, 14, 7);
        List<DayPoint> last30 = dailySeries.tail(points, 30);
        List<DayPoint> prev30 = dailySeries.range(points, 60, 30);

        double gmv7 = dailySeries.sumAmount(last7);
        double prevGmv7 = dailySeries.sumAmount(prev7);
        long orders7 = dailySeries.sumOrders(last7);
        long prevOrders7 = dailySeries.sumOrders(prev7);
        double monthRevenue = dailySeries.sumAmount(last30);
        double prevMonthRevenue = dailySeries.sumAmount(prev30);

        Shop shop = shopMapper.selectById(shopId);
        BigDecimal target = shop != null
                        && shop.getTodayTarget() != null
                        && shop.getTodayTarget().signum() > 0
                ? shop.getTodayTarget()
                : DEFAULT_MONTH_TARGET;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hero", hero(monthRevenue, prevMonthRevenue, orders7, target));
        result.put("kpi", kpi(gmv7, prevGmv7, orders7, prevOrders7, last7, prev7));
        result.put("today", today(points));
        result.put("trend", trend(last7, prev7, last30, prev30));
        // 流量来源依赖埋点，暂为空集合
        result.put("traffic", List.of());
        result.put("todos", todos(shopId));
        result.put("topProducts", statsMapper.topProducts(shopId, 5));
        result.put("stockAlerts", statsMapper.stockAlerts(shopId));
        return result;
    }

    @Override
    public Map<String, Object> analytics() {
        // 看板组装已独立到 MerchantAnalyticsAssembler：
        // 概览与看板是两个页面，数据源与变更节奏都不同，分开放更易维护。
        return analyticsAssembler.assemble();
    }

    @Override
    public List<Map<String, Object>> dailySeries(int days) {
        Long shopId = MerchantSecurityUtils.getShopId();
        return dailySeries.series(shopId, days).stream()
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", p.date().toString());
                    item.put("label", p.label());
                    item.put("amount", Numbers.round(p.amount(), 2));
                    item.put("orders", p.orders());
                    // 访客数依赖埋点，暂无数据源
                    item.put("visitors", 0);
                    return item;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> stockAlerts() {
        return statsMapper.stockAlerts(MerchantSecurityUtils.getShopId());
    }

    /* ------------------------------ 组装 ------------------------------ */

    private Map<String, Object> hero(double monthRevenue, double prevRevenue, long orders, BigDecimal target) {
        Map<String, Object> hero = new LinkedHashMap<>();
        hero.put("monthRevenue", Numbers.round(monthRevenue, 2));
        hero.put("monthGrowth", Numbers.round(Numbers.chainRatio(monthRevenue, prevRevenue), 4));
        // 无埋点，用支付订单数近似「成交客户数」
        hero.put("monthCustomers", orders);
        hero.put("customerGrowth", Numbers.round(Numbers.chainRatio(orders, orders), 4));
        hero.put("pendingSettle", pendingSettle());
        hero.put("targetAmount", target);
        hero.put("targetRate", Numbers.round(Numbers.divide(monthRevenue, target.doubleValue()), 4));
        return hero;
    }

    private List<Map<String, Object>> kpi(
            double gmv7, double prevGmv7, long orders7, long prevOrders7, List<DayPoint> last7, List<DayPoint> prev7) {
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(kpiItem(
                "gmv",
                "成交额",
                Numbers.round(gmv7, 2),
                "¥",
                null,
                Numbers.round(Numbers.chainRatio(gmv7, prevGmv7), 4),
                last7.stream().map(p -> Numbers.round(p.amount(), 2)).toList(),
                "brand"));
        kpi.add(kpiItem(
                "orders",
                "订单数",
                orders7,
                null,
                "笔",
                Numbers.round(Numbers.chainRatio(orders7, prevOrders7), 4),
                last7.stream().map(DayPoint::orders).toList(),
                "green"));
        kpi.add(kpiItem(
                "aov",
                "客单价",
                Numbers.round(Numbers.divide(gmv7, orders7), 2),
                "¥",
                null,
                Numbers.round(
                        Numbers.chainRatio(Numbers.divide(gmv7, orders7), Numbers.divide(prevGmv7, prevOrders7)), 4),
                last7.stream()
                        .map(p -> Numbers.round(Numbers.divide(p.amount(), p.orders()), 2))
                        .toList(),
                "teal"));
        // 访客数依赖埋点，暂以 0 占位
        kpi.add(kpiItem("visitors", "访客数", 0, null, "人", 0d, List.of(), "violet"));
        return kpi;
    }

    private Map<String, Object> kpiItem(
            String key,
            String label,
            Object value,
            String prefix,
            String suffix,
            double delta,
            List<?> trend,
            String tone) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("value", value);
        if (prefix != null) {
            item.put("prefix", prefix);
        }
        if (suffix != null) {
            item.put("suffix", suffix);
        }
        item.put("delta", delta);
        item.put("trend", trend);
        item.put("tone", tone);
        return item;
    }

    private Map<String, Object> today(List<DayPoint> points) {
        DayPoint today = points.isEmpty() ? null : points.get(points.size() - 1);
        DayPoint yesterday = points.size() < 2 ? null : points.get(points.size() - 2);
        Map<String, Object> map = new LinkedHashMap<>();
        double amount = today == null ? 0 : today.amount();
        double prev = yesterday == null ? 0 : yesterday.amount();
        map.put("amount", Numbers.round(amount, 2));
        map.put("orders", today == null ? 0 : today.orders());
        map.put("amountDelta", Numbers.round(Numbers.chainRatio(amount, prev), 4));
        return map;
    }

    private Map<String, Object> trend(
            List<DayPoint> last7, List<DayPoint> prev7, List<DayPoint> last30, List<DayPoint> prev30) {
        return Map.of(
                "week",
                        Map.of(
                                "label", "近 7 天",
                                "labels", last7.stream().map(DayPoint::label).toList(),
                                "current",
                                        last7.stream()
                                                .map(p -> Numbers.round(p.amount(), 2))
                                                .toList(),
                                "previous",
                                        prev7.stream()
                                                .map(p -> Numbers.round(p.amount(), 2))
                                                .toList()),
                "month",
                        Map.of(
                                "label", "近 30 天",
                                "labels", last30.stream().map(DayPoint::label).toList(),
                                "current",
                                        last30.stream()
                                                .map(p -> Numbers.round(p.amount(), 2))
                                                .toList(),
                                "previous",
                                        prev30.stream()
                                                .map(p -> Numbers.round(p.amount(), 2))
                                                .toList()));
    }

    private List<Map<String, Object>> todos(Long shopId) {
        List<Map<String, Object>> todos = new ArrayList<>();
        todos.add(todo("ship", "待发货订单", statsMapper.waitShipCount(shopId), "brand", "超 24 小时将影响体验分", "/shipping"));
        todos.add(todo(
                "after", "售后待处理", statsMapper.aftersalePendingCount(shopId), "coral", "需在 48 小时内响应", "/aftersale"));
        todos.add(todo("review", "评价待回复", statsMapper.reviewWaitCount(shopId), "amber", "影响店铺 DSR 评分", "/reviews"));
        Map<String, Object> product = statsMapper.productSummary(shopId);
        todos.add(todo(
                "stock",
                "库存预警商品",
                Numbers.l(product == null ? null : product.get("warn")),
                "violet",
                "低于安全库存线",
                "/products"));
        return todos;
    }

    private Map<String, Object> todo(String key, String label, long count, String tone, String desc, String to) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("count", count);
        item.put("tone", tone);
        item.put("desc", desc);
        item.put("to", to);
        return item;
    }

    private double pendingSettle() {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Settlement> list = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getShopId, shopId)
                .ne(Settlement::getStatus, "settled"));
        return list.stream().mapToDouble(s -> Numbers.d(s.getSettle())).sum();
    }
}
