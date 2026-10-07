package com.geekmall.modules.trade.job;

import com.geekmall.modules.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单超时未支付自动关闭任务。
 *
 * <p>多实例部署时，{@code @SchedulerLock} 保证同一时刻只有一个实例执行，
 * 避免同一笔订单被重复取消（ShedLock 用 Redis 实现，无需额外中间件）。</p>
 *
 * <p>规模变大后可替换为延迟队列（RabbitMQ 延迟插件 / RocketMQ 延时消息），
 * 避免轮询扫描；当前批次扫描已足够应对中小流量。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final TradeService tradeService;

    /** P2-7：单轮处理条数，积压时可上调，避免「追不上」 */
    @Value("${mall.order.timeout-batch-size:500}")
    private int batchSize;

    @Scheduled(initialDelayString = "${mall.order.timeout-scan-initial-delay-ms:30000}",
            fixedDelayString = "${mall.order.timeout-scan-ms:60000}")
    @SchedulerLock(name = "orderTimeoutJob", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void closeExpiredOrders() {
        try {
            int closed = tradeService.closeExpiredOrders(batchSize);
            if (closed > 0) {
                log.info("订单超时关闭任务完成，本次关闭 {} 笔", closed);
            }
        } catch (Exception e) {
            // 任务层兜底，避免一次异常导致后续调度被取消
            log.error("订单超时关闭任务执行失败", e);
        }
    }
}
