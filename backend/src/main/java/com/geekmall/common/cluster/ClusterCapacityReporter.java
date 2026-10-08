package com.geekmall.common.cluster;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 启动时打印集群容量评估（连接池总量 vs 数据库上限）。
 *
 * <p>把 {@code 实例数 × 单实例池大小} 与数据库 {@code max_connections} 对比，
 * 超限时给 WARN 并明确给出可行上限，让「扩容后连接打满」这类问题在启动阶段就暴露。</p>
 *
 * <p>池大小直接从 {@link HikariDataSource} 读实际生效值，而不是再抄一份配置 ——
 * 避免「配置改了但读的是另一个键」这种自欺欺人的校验。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterCapacityReporter implements ApplicationRunner {

    private final ClusterProperties properties;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isCapacityCheckEnabled()) {
            return;
        }
        PoolCapacity capacity = PoolCapacity.of(properties.getInstanceCount(),
                resolvePoolSize(), properties.getMysqlMaxConnections());
        if (!capacity.known()) {
            log.info("[容量] 未能识别连接池实现，跳过连接数校验（实例数={}）", properties.getInstanceCount());
            return;
        }
        if (capacity.overCapacity()) {
            log.warn("[容量] 连接池总量超出数据库上限：{} 实例 × {} 连接/实例 = {} > max_connections {}；"
                            + "请把 spring.datasource.hikari.maximum-pool-size 调到 {} 以下，或上调数据库 max_connections",
                    capacity.instanceCount(), capacity.poolSize(), capacity.totalConnections(),
                    capacity.mysqlMaxConnections(), capacity.maxPoolSizePerInstance());
        } else {
            log.info("[容量] 连接池评估通过：{} 实例 × {} 连接/实例 = {} ≤ max_connections {}",
                    capacity.instanceCount(), capacity.poolSize(), capacity.totalConnections(),
                    capacity.mysqlMaxConnections());
        }
    }

    /** 读取连接池上限；非 Hikari 实现返回 -1（跳过校验，不做无根据的推断）。 */
    private int resolvePoolSize() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        return -1;
    }
}
