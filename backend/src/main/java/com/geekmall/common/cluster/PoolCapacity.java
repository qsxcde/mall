package com.geekmall.common.cluster;

/**
 * 连接池容量评估（纯逻辑，便于单测）。
 *
 * <p>多实例部署时最容易踩的容量坑：每个实例各持一个连接池，
 * 数据库承受的<b>总连接数 = 单实例池大小 × 实例数</b>。
 * prod 默认池大小 100、MySQL {@code max_connections} 默认 300 ——
 * 也就是说<b>开到第 3 个实例就会把数据库连接打满</b>，表现为大面积
 * 「获取连接超时」，而不是显式的错误。</p>
 *
 * <p>这里把判断抽成纯函数，启动时由 {@link ClusterCapacityReporter} 打印结论，
 * 让配置错误在启动阶段就暴露，而不是等到流量上来。</p>
 *
 * @param instanceCount       集群实例数
 * @param poolSize            单实例连接池上限；{@code <=0} 表示未知（无法校验）
 * @param mysqlMaxConnections 数据库允许的最大连接数
 */
public record PoolCapacity(int instanceCount, int poolSize, int mysqlMaxConnections) {

    public static PoolCapacity of(int instanceCount, int poolSize, int mysqlMaxConnections) {
        return new PoolCapacity(instanceCount, poolSize, mysqlMaxConnections);
    }

    /** 是否具备校验条件（池大小未知时跳过，避免误导性告警）。 */
    public boolean known() {
        return instanceCount > 0 && poolSize > 0 && mysqlMaxConnections > 0;
    }

    /** 集群总连接数 = 实例数 × 单实例池大小。 */
    public long totalConnections() {
        return (long) Math.max(0, instanceCount) * Math.max(0, poolSize);
    }

    /** 是否已超出数据库连接上限。 */
    public boolean overCapacity() {
        return known() && totalConnections() > mysqlMaxConnections;
    }

    /** 在不超限的前提下，按当前实例数还能给每个实例多少连接（至少 1）。 */
    public int maxPoolSizePerInstance() {
        if (instanceCount <= 0 || mysqlMaxConnections <= 0) {
            return 0;
        }
        return Math.max(1, mysqlMaxConnections / instanceCount);
    }
}
