package com.geekmall.modules.marketing.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 秒杀削峰队列的健康指示器（仅在 {@code mall.seckill.async.enabled=true} 时注册）。
 *
 * <p>把队列状态并入 {@code /actuator/health}，让「服务能不能干活」这件事在探针里就可见：
 * 消费者数为 0、或有在途积压时，无需登机器翻日志就能看出来。</p>
 *
 * <h3>为什么积压不让整体健康变 DOWN</h3>
 * <p>刻意<b>只报告状态、不改变 UP/DOWN</b>（{@code status} 详情字段描述真实情况），原因有三：</p>
 * <ol>
 *   <li><b>职责分层</b>：健康检查回答「进程是否可用」，积压属于「服务是否降级」——
 *       后者应由 Prometheus 告警（见 {@code infra/prometheus/rules/mall-alerts.yml}）负责；</li>
 *   <li><b>会影响编排</b>：本项目的 {@code docker-compose.multi.yml} 用
 *       {@code depends_on: condition: service_healthy} 作为启动闸门，Dockerfile 的
 *       HEALTHCHECK 也以文本 {@code UP} 判定；把「有积压」判成 DOWN 会让实例无法启动或被误杀；</li>
 *   <li><b>观测失败不等于业务失败</b>：Redis 抖动导致取不到队列状态时，只记
 *       {@code status=unavailable}，不升级为故障。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.seckill.async.enabled", havingValue = "true")
public class SeckillQueueHealthIndicator implements HealthIndicator {

    /** 积压超过该值即视为「积压」，仅体现在 status 详情里。 */
    private static final long BACKLOG_THRESHOLD = 500L;

    private final SeckillOrderQueue queue;

    @Override
    public Health health() {
        SeckillOrderQueue.QueueStats stats = queue.stats();
        if (!stats.available()) {
            // 取不到状态（Redis 抖动 / 消费组尚未创建）：不判定为故障，只如实标注
            return Health.up()
                    .withDetail("status", "unavailable")
                    .withDetail("note", "无法读取队列状态，按不可用处理（不影响可用性判定）")
                    .build();
        }
        String status;
        if (stats.pending() > BACKLOG_THRESHOLD) {
            status = "backlog";
        } else if (stats.consumers() <= 0) {
            status = "no-consumer";
        } else {
            status = "healthy";
        }
        return Health.up()
                .withDetail("status", status)
                .withDetail("pending", stats.pending())
                .withDetail("length", stats.length())
                .withDetail("consumers", stats.consumers())
                .withDetail("backlogThreshold", BACKLOG_THRESHOLD)
                .build();
    }
}
