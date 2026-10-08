package com.geekmall.common.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 集群 / 部署容量配置（{@code mall.cluster.*}）。
 *
 * <p>单实例部署时这里的默认值即可；<b>多实例部署必须按实际副本数声明 {@code instance-count}</b>，
 * 否则两类「进程内」资源会随实例数被放大：</p>
 * <ul>
 *   <li><b>限流 LOCAL 层</b>：计数在进程内，N 个实例的总放行量 = 配置值 × N。
 *       声明实例数后，每实例只放行 {@code ceil(limit / N)}，全局总量回到配置值；</li>
 *   <li><b>数据库连接池</b>：N 个实例各自持有一个池，总量 = 单实例池大小 × N，
 *       必须小于数据库 {@code max_connections}。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.cluster")
public class ClusterProperties {

    /**
     * 本服务在集群中的实例数（部署时按实际副本数填写）。
     *
     * <p>默认 1 = 单实例语义（不切分配额）。它与 {@code RateLimitPolicy} 的
     * 「每实例额度」计算、以及启动时的连接池容量校验共用。</p>
     */
    private int instanceCount = 1;

    /** 数据库允许的最大连接数（与 MySQL {@code max_connections} 对齐），用于启动时容量校验。 */
    private int mysqlMaxConnections = 300;

    /** 是否在启动时执行容量校验并打印结论。 */
    private boolean capacityCheckEnabled = true;
}
