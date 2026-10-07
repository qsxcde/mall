package com.geekmall.common.log;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("重复日志节流")
class RepeatLogThrottlerTest {

    private RepeatLogThrottler throttler;

    @BeforeEach
    void setUp() {
        throttler = new RepeatLogThrottler(60, 100);
    }

    @Test
    @DisplayName("窗口内首次打完整堆栈，其后只记摘要并累计次数")
    void shouldOnlyReportFirstInWindow() {
        assertThat(throttler.record("NPE@TradeServiceImpl#create").firstInWindow()).isTrue();
        assertThat(throttler.record("NPE@TradeServiceImpl#create").firstInWindow()).isFalse();

        RepeatLogThrottler.Decision third = throttler.record("NPE@TradeServiceImpl#create");
        assertThat(third.firstInWindow()).isFalse();
        assertThat(third.count()).as("次数用于在摘要里体现「重复了多少次」").isEqualTo(3);
    }

    @Test
    @DisplayName("不同指纹互不影响：A 被节流不应连累 B")
    void shouldIsolateFingerprints() {
        throttler.record("A");
        throttler.record("A");

        assertThat(throttler.record("B").firstInWindow()).isTrue();
        assertThat(throttler.record("A").count()).isEqualTo(3);
    }

    @Test
    @DisplayName("窗口过期后重新放行完整堆栈")
    void shouldResetAfterWindow() throws Exception {
        RepeatLogThrottler shortWindow = new RepeatLogThrottler(1, 100);
        assertThat(shortWindow.record("A").firstInWindow()).isTrue();
        assertThat(shortWindow.record("A").firstInWindow()).isFalse();

        Thread.sleep(2500);

        assertThat(shortWindow.record("A").firstInWindow())
                .as("窗口过期后应重新放行，否则「偶发但反复」的错误会永远看不到堆栈")
                .isTrue();
    }
}
