package com.geekmall.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化日志配置断言。
 *
 * <p>「日志改成 JSON」最常见的失败方式是<b>静默失败</b>：配置写错了应用照样启动，
 * 只是 Loki 那边永远解析不出字段。所以把它变成可重复的断言——直接加载真实的
 * {@code logback-spring.xml}，写一条日志，再按 JSON 解析回来。</p>
 *
 * <p>能「不启动 Spring 上下文」就跑起来，是因为配置文件刻意只用了纯 logback 元素
 * （见该文件头部的取舍说明），因此可以用 {@link JoranConfigurator} 离线加载。</p>
 *
 * <p><b>为什么必须用全局 LoggerContext</b>：SLF4J 的 MDC 适配器由它绑定，
 * 另建一个 {@code new LoggerContext()} 会让 MDC 适配器为空——{@code %X{traceId}}
 * 与 JSON 的 {@code <mdc/>} 都取不到值，日志写入会被静默吞掉。
 * 测试结束会把配置还原为「仅控制台」，避免影响同一 JVM 内的其他测试。</p>
 */
@DisplayName("结构化日志配置（logback-spring.xml）")
class StructuredLoggingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOG_FILE_NAME = "geek-mall.json.log";
    private static final String LOGGER_NAME = "com.geekmall.log.demo";

    private static LoggerContext context;
    private static Path logHome;
    private static Logger log;

    @BeforeAll
    static void setUpLogging() throws Exception {
        logHome = Files.createTempDirectory("geek-mall-logs");
        System.setProperty("LOG_HOME", logHome.toString());

        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);

        URL config = StructuredLoggingTest.class.getClassLoader().getResource("logback-spring.xml");
        assertThat(config).as("测试必须基于真实的日志配置文件，否则断言没有意义").isNotNull();
        configurator.doConfigure(config);

        log = context.getLogger(LOGGER_NAME);
    }

    @AfterAll
    static void restoreLogging() {
        // 还原为「仅控制台」的最小配置：否则同一 JVM 内后续测试的日志会写向已被删除的临时目录
        if (context != null) {
            context.reset();
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
            encoder.start();

            ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
            console.setContext(context);
            console.setEncoder(encoder);
            console.start();

            ch.qos.logback.classic.Logger root =
                    context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            root.addAppender(console);
            root.setLevel(Level.INFO);
        }
        System.clearProperty("LOG_HOME");
        deleteQuietly(logHome);
    }

    @Test
    @DisplayName("文件输出为 JSON，traceId/userId 是独立字段而非消息文本的一部分")
    void shouldEmitStructuredJsonWithTraceContext() throws Exception {
        List<String> lines = captureLines(() -> {
            MDC.put(TraceContext.TRACE_ID, "trace-abc-001");
            // 这里用数值 id：手机号形态的值会被脱敏，见 shouldMaskPhoneLikeValueInContextFields
            MDC.put("userId", "8");
            try {
                log.info("下单成功 orderNo={}", "GM202610070001");
            } finally {
                MDC.clear();
            }
        });

        assertThat(lines).as("一次日志事件应产生且仅产生一行").hasSize(1);

        JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.get(TraceContext.TRACE_ID).asText()).isEqualTo("trace-abc-001");
        assertThat(node.get("userId").asText()).isEqualTo("8");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger").asText()).isEqualTo(LOGGER_NAME);
        assertThat(node.get("thread").asText()).isNotBlank();
        assertThat(node.get("message").asText()).isEqualTo("下单成功 orderNo=GM202610070001");
        assertThat(node.get("app").asText()).isEqualTo("geek-mall-server");
        assertThat(node.get("env").asText()).isNotBlank();
        assertThat(node.get("@timestamp").asText()).isNotBlank();
    }

    @Test
    @DisplayName("异常日志仍是「一行一条 JSON」，堆栈不会把日志拆成多行")
    void shouldKeepExceptionLogOnSingleLine() throws Exception {
        List<String> lines = captureLines(() -> log.error("系统异常", new IllegalStateException("boom")));

        assertThat(lines).as("堆栈一旦含换行，Loki 就会把它拆成互不相关的多行").hasSize(1);

        JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.get("level").asText()).isEqualTo("ERROR");
        assertThat(lines.get(0)).contains("IllegalStateException");
    }

    @Test
    @DisplayName("无链路上下文时不应出现 traceId 字段，避免空值污染检索")
    void shouldOmitTraceIdWhenAbsent() throws Exception {
        List<String> lines = captureLines(() -> log.info("定时任务执行：订单超时关闭"));

        JsonNode node = MAPPER.readTree(lines.get(0));
        assertThat(node.has(TraceContext.TRACE_ID)).isFalse();
    }

    @Test
    @DisplayName("自由文本里的手机号被脱敏，且不连带脱敏同一条消息的其它内容")
    void shouldMaskPhoneNumberInMessage() throws Exception {
        List<String> lines = captureLines(() -> log.info("用户 13800000000 登录成功，订单号 GM202610070001"));

        String raw = lines.get(0);
        assertThat(raw).as("手机号不得以明文落盘").doesNotContain("13800000000");
        assertThat(raw).as("脱敏不该把整条消息吞掉——那等于日志没了").contains("订单号 GM202610070001");
    }

    @Test
    @DisplayName("长数字串（毫秒时间戳等）不应被误判为手机号")
    void shouldNotMaskLongerDigitRuns() throws Exception {
        List<String> lines = captureLines(() -> log.info("任务耗时 1759840000000 纳秒"));

        assertThat(lines.get(0))
                .as("13 位数字内部含 11 位数字，脱敏正则必须用边界环视排除")
                .contains("1759840000000");
    }

    @Test
    @DisplayName("上下文字段里的手机号形态值同样被打码（有意为之，防止被「顺手改成不脱敏」）")
    void shouldMaskPhoneLikeValueInContextFields() throws Exception {
        List<String> lines = captureLines(() -> {
            MDC.put("userId", "13800000000");
            try {
                log.info("用户登录");
            } finally {
                MDC.clear();
            }
        });

        assertThat(MAPPER.readTree(lines.get(0)).get("userId").asText()).isEqualTo("******");
    }

    /* ------------------------------ 工具方法 ------------------------------ */

    /**
     * 执行一段日志动作并返回<b>新增</b>的日志行。
     *
     * <p>用「先记行数、再取增量」而不是直接读全文件：多个测试共用同一个日志文件，
     * 直接断言总行数会让测试之间互相干扰。</p>
     */
    private static List<String> captureLines(Runnable action) throws Exception {
        Path file = logHome.resolve(LOG_FILE_NAME);
        int before = readLines(file).size();
        action.run();

        for (int i = 0; i < 50; i++) {
            List<String> all = readLines(file);
            if (all.size() > before) {
                return all.subList(before, all.size());
            }
            Thread.sleep(20);
        }
        assertThat(readLines(file).size())
                .as("日志未被写入 %s —— 通常是 appender/encoder 配置有误（如类名写错）", file)
                .isGreaterThan(before);
        return List.of();
    }

    private static List<String> readLines(Path file) throws Exception {
        if (!Files.exists(file)) {
            return List.of();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响断言结果
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
