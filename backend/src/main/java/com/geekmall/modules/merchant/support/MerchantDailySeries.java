package com.geekmall.modules.merchant.support;

import com.geekmall.modules.merchant.mapper.MerchantStatsMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 商家看板「日序列」计算组件。
 *
 * <h3>为什么单独抽出来</h3>
 * <p>概览（{@code overview}）与数据看板（{@code analytics}）都需要「最近 N 天的
 * 成交额/订单数序列」，并都要做三件容易写错的事：</p>
 * <ol>
 *   <li><b>补齐缺失日期</b>：SQL 的 GROUP BY 只返回有数据的日期，直接画图会出现
 *       横轴断裂或点位错位；</li>
 *   <li><b>统一标签格式</b>：前端按 {@code M/d} 渲染横轴；</li>
 *   <li><b>取环比区间</b>：要区分「近 7 天」与「前 7 天」，边界差一天结果就不可比。</li>
 * </ol>
 * <p>这三件事原先在同一个 397 行的类里，被 overview / analytics / dailySeries 三处
 * 各自调用。拆成独立组件后，两个看板服务共享同一套口径 ——
 * 也避免「治好了概览的补零、忘了看板的补零」这类不一致。</p>
 */
@Component
@RequiredArgsConstructor
public class MerchantDailySeries {

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("M/d");

    private final MerchantStatsMapper statsMapper;

    /** 单个自然日的经营点位。 */
    public record DayPoint(LocalDate date, String label, double amount, long orders) {}

    /**
     * 查询并补齐最近 {@code days} 天的日序列（缺失日期补 0）。
     *
     * @param shopId 店铺 ID
     * @param days   天数，至少按 1 处理
     */
    public List<DayPoint> series(Long shopId, int days) {
        int span = Math.max(days, 1);
        Map<String, Map<String, Object>> byDay = statsMapper.dailySeries(shopId, span).stream()
                .collect(Collectors.toMap(m -> String.valueOf(m.get("day")), m -> m, (a, b) -> a));
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

    /** 取序列末尾 {@code n} 个点位（近 N 天）。 */
    public List<DayPoint> tail(List<DayPoint> points, int n) {
        return points.subList(Math.max(0, points.size() - n), points.size());
    }

    /** 取「倒数第 offset..offset+count」段（用于环比基准）。 */
    public List<DayPoint> range(List<DayPoint> points, int offset, int count) {
        int end = Math.max(0, points.size() - (offset - count));
        int start = Math.max(0, end - count);
        return points.subList(start, end);
    }

    /** 区间成交额合计。 */
    public double sumAmount(List<DayPoint> points) {
        return points.stream().mapToDouble(DayPoint::amount).sum();
    }

    /** 区间订单数合计。 */
    public long sumOrders(List<DayPoint> points) {
        return points.stream().mapToLong(DayPoint::orders).sum();
    }
}
