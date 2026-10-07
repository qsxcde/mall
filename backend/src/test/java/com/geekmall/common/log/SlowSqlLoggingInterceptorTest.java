package com.geekmall.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢 SQL 采集断言。
 *
 * <p>直接断言「判定 + 上报」这一段，不拉起 MyBatis 与数据库：拦截器里真正会写错的
 * 是阈值口径、单行化与截断，而不是 AOP 织入本身。</p>
 */
@DisplayName("慢 SQL 采集")
class SlowSqlLoggingInterceptorTest {

    private static final long THRESHOLD_MS = 200;

    private SimpleMeterRegistry registry;
    private SlowSqlLoggingInterceptor interceptor;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger targetLogger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        interceptor = new SlowSqlLoggingInterceptor(THRESHOLD_MS, registry);

        targetLogger = ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(SlowSqlLoggingInterceptor.class);
        targetLogger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(appender);
        registry.close();
    }

    @Test
    @DisplayName("阈值边界：等于阈值算慢，小于阈值不算")
    void shouldJudgeByThreshold() {
        assertThat(interceptor.isSlow(THRESHOLD_MS - 1)).isFalse();
        assertThat(interceptor.isSlow(THRESHOLD_MS)).isTrue();
        assertThat(interceptor.isSlow(THRESHOLD_MS + 1)).isTrue();
    }

    @Test
    @DisplayName("慢 SQL 同时产出日志与指标，且 SQL 被压成单行")
    void shouldLogAndCount() {
        interceptor.reportSlowSql(350, "com.geekmall.modules.trade.mapper.OrderMapper.selectPage",
                "SELECT *\n  FROM t_order\n WHERE id = ?");

        List<String> messages = messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0))
                .contains("慢 SQL")
                .contains("costMs=350")
                .contains("thresholdMs=200")
                .contains("statement=com.geekmall.modules.trade.mapper.OrderMapper.selectPage")
                .as("多行 SQL 会把一条日志拆成多条，破坏「一行一条」约定")
                .doesNotContain("\n");
        assertThat(messages.get(0)).contains("SELECT * FROM t_order WHERE id = ?");

        assertThat(registry.get("mall_slow_sql_total").counter().count())
                .as("日志定位「哪一条」，指标回答「是否在变多」")
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("超长 SQL 被截断，避免单条日志淹没整个文件")
    void shouldTruncateLongSql() {
        interceptor.reportSlowSql(900, "X.y", "SELECT " + "a".repeat(2000) + " FROM t");

        assertThat(messages().get(0)).contains("...(已截断)");
    }

    private List<String> messages() {
        return appender.list.stream().map(event -> event.getFormattedMessage()).toList();
    }
}
