package com.geekmall.common.log;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 慢 SQL 采集：只记录「超过阈值」的 SQL。
 *
 * <p>项目此前在慢 SQL 上是<b>两个极端</b>——dev 用 {@code Slf4jImpl} 打印全量 SQL（噪音淹没日志），
 * prod 用 {@code NoLoggingImpl} 直接关掉（生产完全没有慢 SQL 入口）。这里补上缺失的中间态：
 * <b>平时静默，超阈值才出声</b>，同时计入 {@code mall_slow_sql_total} 指标。
 * 日志负责回答「是哪一条 SQL」，指标负责回答「是否在变多」。</p>
 *
 * <p><b>刻意不打印绑定参数</b>：参数里可能有手机号、收货地址等个人信息，
 * 打全量参数既违反脱敏要求，也会让日志体积失控。需要看具体参数时，
 * 用同一条 {@code traceId} 回到该请求的其它日志与业务上下文。</p>
 */
@Slf4j
@Component
@Intercepts({
        // 只拦截 4 参 query：6 参版本由它内部调用，两个都拦会导致同一次查询被计时两次
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlLoggingInterceptor implements Interceptor {

    /** SQL 文本截断长度：慢 SQL 往往很长，全量打出来只会淹没日志 */
    private static final int MAX_SQL_LENGTH = 500;

    private final long thresholdMs;
    private final Counter slowSqlCounter;

    public SlowSqlLoggingInterceptor(@Value("${mall.logging.slow-sql-threshold-ms:200}") long thresholdMs,
                                     MeterRegistry meterRegistry) {
        this.thresholdMs = thresholdMs;
        this.slowSqlCounter = Counter.builder("mall_slow_sql_total")
                .description("执行耗时超过阈值的 SQL 次数")
                .tag("dependency", "mysql")
                .register(meterRegistry);
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            // 只有慢调用才去取 MappedStatement / 渲染 SQL —— getBoundSql 本身有开销，
            // 不能因为「采样」反而给每条正常 SQL 都加上成本
            if (isSlow(costMs)) {
                MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
                reportSlowSql(costMs, statement.getId(), rawSqlOf(invocation));
            }
        }
    }

    /** 阈值判定。 */
    boolean isSlow(long costMs) {
        return costMs >= thresholdMs;
    }

    /** 上报慢 SQL：日志给「哪一条」，指标给「趋势」。 */
    void reportSlowSql(long costMs, String statementId, String rawSql) {
        slowSqlCounter.increment();
        log.warn("慢 SQL costMs={} thresholdMs={} statement={} sql={}",
                costMs, thresholdMs, statementId, compact(rawSql));
    }

    private static String rawSqlOf(Invocation invocation) {
        try {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
            BoundSql boundSql = statement.getBoundSql(parameter);
            return boundSql.getSql();
        } catch (RuntimeException ex) {
            // 采样失败绝不能影响主流程
            return "<unavailable: " + ex.getClass().getSimpleName() + ">";
        }
    }

    /** 压成单行并截断：多行 SQL 会让一条日志散成多行，破坏「一行一条」的约定。 */
    static String compact(String sql) {
        if (sql == null) {
            return "<none>";
        }
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_SQL_LENGTH
                ? oneLine
                : oneLine.substring(0, MAX_SQL_LENGTH) + "...(已截断)";
    }
}
