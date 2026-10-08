package com.geekmall.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流规则的「按实例数切分」单元测试（P1-4）。
 *
 * <p>LOCAL 层计数在进程内，N 个实例的总放行量会被放大 N 倍。切分逻辑要保证三件事：</p>
 * <ol>
 *   <li>单实例时额度完全不变（不能因为引入切分就把单机行为改了）；</li>
 *   <li>多实例时向上取整分摊，全局总量回到配置值附近；</li>
 *   <li>分摊结果<b>至少为 1</b> —— 否则「5 次/60s + 10 实例」会算出 0，等于禁用接口。</li>
 * </ol>
 */
class RateLimitPolicyTest {

    private static RateLimitPolicy local(int limit, int maxConcurrent) {
        return new RateLimitPolicy("t-local", Set.of(HttpMethod.POST), List.of("/x"),
                RateLimitDimension.IP, limit, 60, maxConcurrent,
                RateLimitTier.LOCAL, RateLimitFallback.FAIL_OPEN);
    }

    private static RateLimitPolicy distributed(int limit) {
        return new RateLimitPolicy("t-dist", Set.of(HttpMethod.POST), List.of("/x"),
                RateLimitDimension.IP, limit, 60, 0,
                RateLimitTier.DISTRIBUTED, RateLimitFallback.FAIL_CLOSED);
    }

    @Test
    @DisplayName("单实例：额度原样返回，行为与引入切分前一致")
    void singleInstanceKeepsConfiguredLimit() {
        assertThat(local(200, 10).effectiveLimit(1)).isEqualTo(200);
        assertThat(local(200, 10).effectiveMaxConcurrent(1)).isEqualTo(10);
    }

    @Test
    @DisplayName("多实例：向上取整分摊，全局总量回到配置值附近")
    void shardsAcrossInstances() {
        // 200 / 3 = 66.67 → 67；3 × 67 = 201，略高于 200 但不放大 3 倍
        assertThat(local(200, 0).effectiveLimit(3)).isEqualTo(67);
        // 整除场景
        assertThat(local(200, 0).effectiveLimit(4)).isEqualTo(50);
    }

    @Test
    @DisplayName("分摊结果至少为 1：额度再小也不能把接口锁死")
    void shardNeverDropsToZero() {
        // 5 次/60s 在 10 个实例下取整会是 0 → 必须兜底为 1
        assertThat(local(5, 0).effectiveLimit(10)).isEqualTo(1);
    }

    @Test
    @DisplayName("DISTRIBUTED 层全局准确，不参与切分")
    void distributedTierIsNotSharded() {
        assertThat(distributed(10).effectiveLimit(5)).isEqualTo(10);
    }

    @Test
    @DisplayName("并发槽位同样按实例数切分；未配置（0）时保持不限制")
    void shardsMaxConcurrent() {
        assertThat(local(100, 10).effectiveMaxConcurrent(3)).isEqualTo(4);
        assertThat(local(100, 0).effectiveMaxConcurrent(3)).isZero();
    }
}
