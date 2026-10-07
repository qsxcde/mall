package com.geekmall.modules.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.modules.merchant.entity.Settlement;
import com.geekmall.modules.merchant.entity.Shop;
import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import com.geekmall.modules.merchant.mapper.SettlementMapper;
import com.geekmall.modules.merchant.mapper.ShopMapper;
import com.geekmall.modules.merchant.service.MerchantDashboardService;
import com.geekmall.modules.merchant.support.Numbers;
import com.geekmall.modules.merchant.vo.NavBadgesVO;
import com.geekmall.security.MerchantSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        List<DayPoint> points = series(60);

        List<DayPoint> last7 = tail(points, 7);
        List<DayPoint> prev7 = range(points, 14, 7);
        List<DayPoint> last30 = tail(points, 30);
        List<DayPoint> prev30 = range(points, 60, 30);

        double gmv7 = sumAmount(last7);
        double prevGmv7 = sumAmount(prev7);
        long orders7 = sumOrders(last7);
        long prevOrders7 = sumOrders(prev7);
        double monthRevenue = sumAmount(last30);
        double prevMonthRevenue = sumAmount(prev30);

        Shop shop = shopMapper.selectById(shopId);
        BigDecimal target = shop != null && shop.getTodayTarget() != null && shop.getTodayTarget().signum() > 0
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
        Long shopId = MerchantSecurityUtils.getShopId();
        List<DayPoint> points = series(60);
        List<DayPoint> last30 = tail(points, 30);
        List<DayPoint> prev30 = range(points, 60, 30);

        double gmv = sumAmount(last30);
        double prevGmv = sumAmount(prev30);
        long orders = sumOrders(last30);
        long prevOrders = sumOrders(prev30);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", analyticsSummary(gmv, prevGmv, orders, prevOrders));
        result.put("trend", Map.of(
                "labels", last30.stream().map(DayPoint::label).toList(),
                "amount", last30.stream().map(DayPoint::amount).toList(),
                "orders", last30.stream().map(DayPoint::orders).toList()
        ));
        result.put("categories", categories(shopId));
        result.put("hourly", hourly(shopId));
        result.put("regions", regions(shopId));
        result.put("customerMix", customerMix(shopId));
        // 流量渠道与转化漏斗依赖前端埋点（曝光/点击），当前无数据源，返回空集合；
        // 前端在拿到空集合时会隐藏对应卡片，而不是展示 0 值的假图表
        result.put("channels", List.of());
        result.put("funnel", List.of());
        return result;
    }

    @Override
    public List<Map<String, Object>> dailySeries(int days) {
        Long shopId = MerchantSecurityUtils.getShopId();
        return series(shopId, days).stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", p.date().toString());
            item.put("label", p.label());
            item.put("amount", Numbers.round(p.amount(), 2));
            item.put("orders", p.orders());
            // 访客数依赖埋点，暂无数据源
            item.put("visitors", 0);
            return item;
        }).collect(Collectors.toList());
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

    private List<Map<String, Object>> kpi(double gmv7, double prevGmv7, long orders7, long prevOrders7,
                                          List<DayPoint> last7, List<DayPoint> prev7) {
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(kpiItem("gmv", "成交额", Numbers.round(gmv7, 2), "¥", null,
                Numbers.round(Numbers.chainRatio(gmv7, prevGmv7), 4),
                last7.stream().map(p -> Numbers.round(p.amount(), 2)).toList(), "brand"));
        kpi.add(kpiItem("orders", "订单数", orders7, null, "笔",
                Numbers.round(Numbers.chainRatio(orders7, prevOrders7), 4),
                last7.stream().map(DayPoint::orders).toList(), "green"));
        kpi.add(kpiItem("aov", "客单价", Numbers.round(Numbers.divide(gmv7, orders7), 2), "¥", null,
                Numbers.round(Numbers.chainRatio(Numbers.divide(gmv7, orders7), Numbers.divide(prevGmv7, prevOrders7)), 4),
                last7.stream().map(p -> Numbers.round(Numbers.divide(p.amount(), p.orders()), 2)).toList(), "teal"));
        // 访客数依赖埋点，暂以 0 占位
        kpi.add(kpiItem("visitors", "访客数", 0, null, "人", 0d, List.of(), "violet"));
        return kpi;
    }

    private Map<String, Object> kpiItem(String key, String label, Object value, String prefix, String suffix,
                                        double delta, List<?> trend, String tone) {
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

    private Map<String, Object> trend(List<DayPoint> last7, List<DayPoint> prev7,
                                      List<DayPoint> last30, List<DayPoint> prev30) {
        return Map.of(
                "week", Map.of(
                        "label", "近 7 天",
                        "labels", last7.stream().map(DayPoint::label).toList(),
                        "current", last7.stream().map(p -> Numbers.round(p.amount(), 2)).toList(),
                        "previous", prev7.stream().map(p -> Numbers.round(p.amount(), 2)).toList()),
                "month", Map.of(
                        "label", "近 30 天",
                        "labels", last30.stream().map(DayPoint::label).toList(),
                        "current", last30.stream().map(p -> Numbers.round(p.amount(), 2)).toList(),
                        "previous", prev30.stream().map(p -> Numbers.round(p.amount(), 2)).toList())
        );
    }

    private List<Map<String, Object>> todos(Long shopId) {
        List<Map<String, Object>> todos = new ArrayList<>();
        todos.add(todo("ship", "待发货订单", statsMapper.waitShipCount(shopId), "brand", "超 24 小时将影响体验分", "/shipping"));
        todos.add(todo("after", "售后待处理", statsMapper.aftersalePendingCount(shopId), "coral", "需在 48 小时内响应", "/aftersale"));
        todos.add(todo("review", "评价待回复", statsMapper.reviewWaitCount(shopId), "amber", "影响店铺 DSR 评分", "/reviews"));
        Map<String, Object> product = statsMapper.productSummary(shopId);
        todos.add(todo("stock", "库存预警商品", Numbers.l(product == null ? null : product.get("warn")),
                "violet", "低于安全库存线", "/products"));
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

    private List<Map<String, Object>> analyticsSummary(double gmv, double prevGmv, long orders, long prevOrders) {
        List<Map<String, Object>> summary = new ArrayList<>();
        summary.add(analyticsItem("gmv", "成交额", Numbers.round(gmv, 2), "¥", false, Numbers.round(Numbers.chainRatio(gmv, prevGmv), 4), "brand"));
        summary.add(analyticsItem("orders", "支付订单", orders, null, false, Numbers.round(Numbers.chainRatio(orders, prevOrders), 4), "green"));
        // 转化率依赖访客数（埋点），暂以 0 占位
        summary.add(analyticsItem("convert", "支付转化率", 0d, null, true, 0d, "teal"));
        summary.add(analyticsItem("aov", "客单价", Numbers.round(Numbers.divide(gmv, orders), 2), "¥", false,
                Numbers.round(Numbers.chainRatio(Numbers.divide(gmv, orders), Numbers.divide(prevGmv, prevOrders)), 4), "violet"));
        summary.add(analyticsItem("uv", "访客数", 0, null, false, 0d, "amber"));
        return summary;
    }

    private Map<String, Object> analyticsItem(String key, String label, Object value, String prefix,
                                              boolean percent, double delta, String tone) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("value", value);
        if (prefix != null) {
            item.put("prefix", prefix);
        }
        if (percent) {
            item.put("percent", true);
        }
        item.put("delta", delta);
        item.put("tone", tone);
        return item;
    }

    private List<Map<String, Object>> categories(Long shopId) {
        List<Map<String, Object>> raw = statsMapper.categorySales(shopId);
        double total = raw.stream().mapToDouble(m -> Numbers.d(m.get("amount"))).sum();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            double amount = Numbers.d(row.get("amount"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("name"));
            item.put("value", Numbers.round(Numbers.divide(amount, total), 4));
            item.put("amount", Numbers.round(amount, 2));
            list.add(item);
        }
        return list;
    }

    /** 成交额按收货省份分布（TOP 8）。 */
    private List<Map<String, Object>> regions(Long shopId) {
        List<Map<String, Object>> raw = statsMapper.regionSales(shopId);
        double total = raw.stream().mapToDouble(m -> Numbers.d(m.get("amount"))).sum();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : raw.stream().limit(8).toList()) {
            double amount = Numbers.d(row.get("amount"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("province"));
            item.put("value", Numbers.round(Numbers.divide(amount, total), 4));
            item.put("amount", Numbers.round(amount, 2));
            list.add(item);
        }
        return list;
    }

    /** 新老客结构：在店内累计成交 1 次为新客，≥2 次为老客复购。 */
    private List<Map<String, Object>> customerMix(Long shopId) {
        Map<String, Object> raw = statsMapper.customerMix(shopId);
        long newCount = Numbers.l(raw == null ? null : raw.get("newCount"));
        long repeatCount = Numbers.l(raw == null ? null : raw.get("repeatCount"));
        long total = newCount + repeatCount;
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(customerItem("新客", newCount, total));
        list.add(customerItem("老客复购", repeatCount, total));
        return list;
    }

    private Map<String, Object> customerItem(String name, long count, long total) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", Numbers.round(Numbers.divide(count, total), 4));
        item.put("count", count);
        return item;
    }

    private List<Map<String, Object>> hourly(Long shopId) {
        Map<Integer, Long> counts = statsMapper.hourlyOrders(shopId).stream()
                .collect(Collectors.toMap(
                        m -> Numbers.i(m.get("hour")),
                        m -> Numbers.l(m.get("cnt"))));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int h = 0; h < 24; h += 1) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", h);
            item.put("value", counts.getOrDefault(h, 0L));
            list.add(item);
        }
        return list;
    }

    private double pendingSettle() {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<Settlement> list = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getShopId, shopId)
                .ne(Settlement::getStatus, "settled"));
        return list.stream().mapToDouble(s -> Numbers.d(s.getSettle())).sum();
    }

    /* ------------------------------ 日序列 ------------------------------ */

    private List<DayPoint> series(int days) {
        return series(MerchantSecurityUtils.getShopId(), days);
    }

    /** 查询并补齐最近 days 天的日序列（缺失日期补 0）。 */
    private List<DayPoint> series(Long shopId, int days) {
        int span = Math.max(days, 1);
        Map<String, Map<String, Object>> byDay = statsMapper.dailySeries(shopId, span).stream()
                .collect(Collectors.toMap(
                        m -> String.valueOf(m.get("day")),
                        m -> m,
                        (a, b) -> a));
        List<DayPoint> points = new ArrayList<>(span);
        LocalDate today = LocalDate.now();
        for (int i = span - 1; i >= 0; i -= 1) {
            LocalDate date = today.minusDays(i);
            Map<String, Object> row = byDay.get(date.toString());
            double amount = row == null ? 0d : Numbers.d(row.get("amount"));
            long orders = row == null ? 0L : Numbers.l(row.get("orders"));
            points.add(new DayPoint(date, date.format(LABEL_FMT), amount, orders));
        }
        return points;
    }

    private List<DayPoint> tail(List<DayPoint> points, int n) {
        return points.subList(Math.max(0, points.size() - n), points.size());
    }

    /** 取「倒数第 offset..offset+count」段（用于环比基准）。 */
    private List<DayPoint> range(List<DayPoint> points, int offset, int count) {
        int end = Math.max(0, points.size() - (offset - count));
        int start = Math.max(0, end - count);
        return points.subList(start, end);
    }

    private double sumAmount(List<DayPoint> points) {
        return points.stream().mapToDouble(DayPoint::amount).sum();
    }

    private long sumOrders(List<DayPoint> points) {
        return points.stream().mapToLong(DayPoint::orders).sum();
    }

    /** 单个自然日的经营点位。 */
    private record DayPoint(LocalDate date, String label, double amount, long orders) {
    }
}
