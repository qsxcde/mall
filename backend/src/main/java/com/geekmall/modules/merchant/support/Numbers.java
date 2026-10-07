package com.geekmall.modules.merchant.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 聚合结果取值工具。
 *
 * <p>MyBatis 聚合查询（SUM/COUNT）在无匹配行时会返回 null，且不同驱动返回的数值类型
 * 可能是 Long / BigDecimal / Double。这里统一收敛，避免业务层到处判空与强转。</p>
 */
public final class Numbers {

    private Numbers() {
    }

    /** 转 long，null 视为 0。 */
    public static long l(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /** 转 int，null 视为 0。 */
    public static int i(Object value) {
        return (int) l(value);
    }

    /** 转 double，null 视为 0。 */
    public static double d(Object value) {
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    /** 转 BigDecimal，保留 2 位，null 视为 0。 */
    public static BigDecimal bd(Object value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    /** 环比：(本期 - 上期) / 上期，上期为 0 时返回 0，避免 Infinity。 */
    public static double chainRatio(double current, double previous) {
        if (previous == 0d) {
            return 0d;
        }
        return (current - previous) / previous;
    }

    /** 安全除法，分母为 0 返回 0。 */
    public static double divide(double numerator, double denominator) {
        if (denominator == 0d) {
            return 0d;
        }
        return numerator / denominator;
    }

    /** 保留 n 位小数。 */
    public static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
