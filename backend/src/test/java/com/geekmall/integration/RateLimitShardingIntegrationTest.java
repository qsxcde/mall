package com.geekmall.integration;

import com.geekmall.common.cluster.ClusterProperties;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「LOCAL 配额按实例数切分」的端到端验证（P1-4）。
 *
 * <p>用真实拦截器 + 真实配置（{@code mall.cluster.instance-count=4}）跑一遍，
 * 并通过响应头 {@code X-RateLimit-Limit} 读回<b>实际生效的额度</b> ——
 * 这个头就是拦截器传给限流器的阈值，因此能确定性地证明切分真的生效，
 * 而不是只验证「某个私有方法算得对」。</p>
 *
 * <p>为什么必须打开限流：{@code application-test.yml} 为业务流程用例关掉了限流，
 * 这里显式打开（与 {@code RateLimitIntegrationTest} 同一手法）。</p>
 */
@SpringBootTest(properties = {
        "mall.rate-limit.enabled=true",
        "mall.cluster.instance-count=4"
})
@DisplayName("限流 LOCAL 配额按实例数切分（P1-4）")
class RateLimitShardingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ClusterProperties clusterProperties;

    /**
     * MockMvc 下所有请求来自同一 IP，计数键会在用例间互相干扰，
     * 因此每个用例前清空限流计数（与 RateLimitIntegrationTest 一致）。
     */
    @BeforeEach
    void resetRateLimitCounters() {
        var keys = stringRedisTemplate.keys("mall:rate:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("配置项正确绑定：instance-count=4 必须真的读到 4")
    void clusterPropertiesAreBound() {
        assertThat(clusterProperties.getInstanceCount())
                .as("mall.cluster.instance-count 绑定失败会静默退化为单实例语义，切分形同没做")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("LOCAL 规则：额度被切分（product-read 配置 200/1s，4 实例 → 每实例 50）")
    void localRuleLimitIsSharded() throws Exception {
        MockHttpServletResponse response = mockMvc
                .perform(MockMvcRequestBuilders.get("/api/v1/products/1"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit"))
                .as("LOCAL 层计数在进程内，4 实例下每实例额度应为 ceil(200/4) = 50")
                .isEqualTo("50");
    }

    @Test
    @DisplayName("DISTRIBUTED 规则：全局计数，不切分（auth-sms-code 仍是 5/60s）")
    void distributedRuleLimitIsNotSharded() throws Exception {
        MockHttpServletResponse response = mockMvc
                .perform(MockMvcRequestBuilders.post("/api/v1/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("phone", randomPhone(), "scene", "register"))))
                .andReturn().getResponse();

        assertThat(response.getHeader("X-RateLimit-Limit"))
                .as("分布式层本就是跨实例全局准确，切分反而会把额度缩小 4 倍")
                .isEqualTo("5");
    }
}
