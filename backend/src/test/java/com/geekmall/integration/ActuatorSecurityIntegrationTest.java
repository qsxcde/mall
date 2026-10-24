package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Actuator 端点鉴权矩阵回归测试。
 *
 * <p>守住的不变量：<b>健康检查匿名可达</b>（探针 / HEALTHCHECK / 压测脚本不带凭证），
 * 而 info / metrics / prometheus 等管理端点<b>必须持观测凭证</b> —— 指标里含业务量
 * 与内部结构信息，不能匿名抓取。改坏了（比如把 /actuator/** 重新放回白名单）会立刻红灯。</p>
 */
@DisplayName("Actuator 端点鉴权")
class ActuatorSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String OBS_PASSWORD = "dev-obs-password-change-me";

    private static final String OBS_BASIC =
            "Basic " + Base64.getEncoder().encodeToString(("obs:" + OBS_PASSWORD).getBytes(StandardCharsets.UTF_8));

    private static final String WRONG_BASIC =
            "Basic " + Base64.getEncoder().encodeToString("obs:wrong-password".getBytes(StandardCharsets.UTF_8));

    private int rawStatus(String url, String authorization) throws Exception {
        var builder = MockMvcRequestBuilders.get(url);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        return result.getResponse().getStatus();
    }

    @Test
    @DisplayName("健康检查匿名可达：探针不带凭证，返回 200")
    void healthIsAnonymous() throws Exception {
        assertThat(rawStatus("/actuator/health", null)).isEqualTo(200);
    }

    @Test
    @DisplayName("指标端点匿名访问 → 401（业务指标不再公开可读）")
    void metricsAndPrometheusRequireCredentials() throws Exception {
        assertThat(rawStatus("/actuator/prometheus", null)).isEqualTo(401);
        assertThat(rawStatus("/actuator/metrics", null)).isEqualTo(401);
        assertThat(rawStatus("/actuator/info", null)).isEqualTo(401);
    }

    @Test
    @DisplayName("观测账号 HTTP Basic 可抓取指标：Prometheus basic_auth 链路可用")
    void obsAccountCanScrapeMetrics() throws Exception {
        assertThat(rawStatus("/actuator/prometheus", OBS_BASIC)).isEqualTo(200);
        assertThat(rawStatus("/actuator/metrics", OBS_BASIC)).isEqualTo(200);
    }

    @Test
    @DisplayName("错误的观测口令 → 401")
    void wrongObsPasswordIsRejected() throws Exception {
        assertThat(rawStatus("/actuator/prometheus", WRONG_BASIC)).isEqualTo(401);
    }

    @Test
    @DisplayName("买家业务令牌不等于观测凭证：JWT 访问指标端点 → 401")
    void businessTokenCannotScrapeMetrics() throws Exception {
        String token = loginDemoBuyer();
        assertThat(rawStatus("/actuator/prometheus", "Bearer " + token)).isEqualTo(401);
    }
}
