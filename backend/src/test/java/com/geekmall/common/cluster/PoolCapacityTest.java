package com.geekmall.common.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接池容量评估单元测试。
 *
 * <p>这条规则的价值在于<b>边界</b>：{@code 实例数 × 池大小} 恰好等于数据库上限是合法的，
 * 多一个实例才超限。算错这里会导致「要么过早告警，要么该告警时沉默」。</p>
 */
class PoolCapacityTest {

    @Test
    @DisplayName("单实例：50 × 1 = 50，远低于上限")
    void singleInstanceIsFine() {
        PoolCapacity capacity = PoolCapacity.of(1, 50, 300);

        assertThat(capacity.known()).isTrue();
        assertThat(capacity.totalConnections()).isEqualTo(50);
        assertThat(capacity.overCapacity()).isFalse();
    }

    @Test
    @DisplayName("边界：3 实例 × 100 = 300 恰好等于上限 → 不算超限")
    void exactlyAtLimitIsNotOverCapacity() {
        PoolCapacity capacity = PoolCapacity.of(3, 100, 300);

        assertThat(capacity.totalConnections()).isEqualTo(300);
        assertThat(capacity.overCapacity()).isFalse();
    }

    @Test
    @DisplayName("越界：4 实例 × 100 = 400 > 300 → 超限")
    void exceedingLimitIsDetected() {
        PoolCapacity capacity = PoolCapacity.of(4, 100, 300);

        assertThat(capacity.totalConnections()).isEqualTo(400);
        assertThat(capacity.overCapacity()).isTrue();
    }

    @Test
    @DisplayName("给出不超限前提下的每实例可行上限")
    void suggestsFeasiblePoolSize() {
        // 4 实例、上限 300 → 每个实例最多 75
        assertThat(PoolCapacity.of(4, 100, 300).maxPoolSizePerInstance()).isEqualTo(75);
        // 7 实例、上限 300 → 300/7 = 42（向下取整，保证总量不超）
        assertThat(PoolCapacity.of(7, 100, 300).maxPoolSizePerInstance()).isEqualTo(42);
    }

    @Test
    @DisplayName("池大小未知（非 Hikari）时跳过校验，不产生误导性告警")
    void unknownPoolSizeSkipsCheck() {
        PoolCapacity capacity = PoolCapacity.of(4, -1, 300);

        assertThat(capacity.known()).isFalse();
        assertThat(capacity.overCapacity()).isFalse();
    }
}
