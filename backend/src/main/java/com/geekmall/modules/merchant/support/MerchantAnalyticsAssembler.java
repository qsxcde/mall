package com.geekmall.modules.merchant.support;

import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import com.geekmall.modules.merchant.support.MerchantDailySeries.DayPoint;
import com.geekmall.security.MerchantSecurityUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 商家「数据看板」组装器（analytics 页面）。
 *
 * <h3>为什么从 MerchantDashboardServiceImpl 拆出来</h3>
 * <p>原类 397 行，同时承担两个<b>独立页面</b>的组装：</p>
 * <ul>
 *   <li>{@code overview()} —— 经营概览（hero / kpi / today / trend / todos）</li>
 *   <li>{@code analytics()} —— 数据看板（summary / 分类构成 / 时段分布 / 地域 / 新老客）</li>
 * </ul>
 * <p>两者的数据源、组装逻辑、变更节奏都不同（概览改版不影响看板，反之亦然），
 * 放在一个类里导致：任何一个页面的微调都要动同一个文件，且有 20+ 个私有方法
 * 挤在一起难以定位。按页面拆分后，两个类各约 200 行，各自只需读懂自己的那部分。</p>
 *
 * <p><b>共享部分不复制</b>：日序列补齐、环比区间、求和等逻辑抽到
 * {@link MerchantDailySeries}，两个页面共用同一套口径 ——
 * 否则很容易出现「概览修好了补零、看板忘了」的不一致。</p>
 */
@Component
@RequiredArgsConstructor
public class MerchantAnalyticsAssembler {

    private final MerchantStatsMapper statsMapper;
    private final MerchantDailySeries dailySeries;

    /** 看板统计窗口（天）。 */
    private static final int WINDOW_DAYS = 60;
    /** 环比对照窗口（天）。 */
    private static final int COMPARE_DAYS = 30;

    /** 组装数据看板。 */
    public Map<String, Object> assemble() {
        Long shopId = MerchantSecurityUtils.getShopId();
        List<DayPoint> points = dailySeries.series(shopId, WINDOW_DAYS);
        List<DayPoint> last30 = dailySeries.tail(points, COMPARE_DAYS);
        List<DayPoint> prev30 = dailySeries.range(points, WINDOW_DAYS, COMPARE_DAYS);

        double gmv = dailySeries.sumAmount(last30);
        double prevGmv = dailySeries.sumAmount(prev30);
        long orders = dailySeries.sumOrders(last30);
        long prevOrders = dailySeries.sumOrders(prev30);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary(gmv, prevGmv, orders, prevOrders));
        result.put(
                "trend",
                Map.of(
                        "labels", last30.stream().map(DayPoint::label).toList(),
                        "amount", last30.stream().map(DayPoint::amount).toList(),
                        "orders", last30.stream().map(DayPoint::orders).toList()));
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

    private List<Map<String, Object>> summary(double gmv, double prevGmv, long orders, long prevOrders) {
        List<Map<String, Object>> summary = new ArrayList<>();
        summary.add(item(
                "gmv",
                "成交额",
                Numbers.round(gmv, 2),
                "¥",
                false,
                Numbers.round(Numbers.chainRatio(gmv, prevGmv), 4),
                "brand"));
        summary.add(item(
                "orders",
                "支付订单",
                orders,
                null,
                false,
                Numbers.round(Numbers.chainRatio(orders, prevOrders), 4),
                "green"));
        // 转化率依赖访客数（埋点），暂以 0 占位
        summary.add(item("convert", "支付转化率", 0d, null, true, 0d, "teal"));
        summary.add(item(
                "aov",
                "客单价",
                Numbers.round(Numbers.divide(gmv, orders), 2),
                "¥",
                false,
                Numbers.round(Numbers.chainRatio(Numbers.divide(gmv, orders), Numbers.divide(prevGmv, prevOrders)), 4),
                "violet"));
        summary.add(item("uv", "访客数", 0, null, false, 0d, "amber"));
        return summary;
    }

    private Map<String, Object> item(
            String key, String label, Object value, String prefix, boolean percent, double delta, String tone) {
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
                .collect(Collectors.toMap(m -> Numbers.i(m.get("hour")), m -> Numbers.l(m.get("cnt"))));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int h = 0; h < 24; h += 1) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", h);
            item.put("value", counts.getOrDefault(h, 0L));
            list.add(item);
        }
        return list;
    }
}
