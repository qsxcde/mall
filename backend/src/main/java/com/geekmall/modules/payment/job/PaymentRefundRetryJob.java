package com.geekmall.modules.payment.job;

import com.geekmall.modules.payment.service.PaymentRefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款失败重试任务。
 *
 * <p>渠道退款是同步模型，失败后原实现只会把退款单置为失败并打日志，没有第二次机会。
 * 本任务按退款单维度重试，复用同一个 {@code refund_no}（渠道侧 out_request_no 幂等），
 * 因此即使上次「渠道已退款、结算未落库」也能安全重放。</p>
 *
 * <p>多实例部署时 {@link SchedulerLock} 保证同一时刻只有一个实例执行；
 * 单条退款单再由 {@code retry_count} 乐观锁二次保护。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.payment.refund-retry.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRefundRetryJob {

    private final PaymentRefundService paymentRefundService;

    /** 单轮处理条数，积压时可上调。 */
    @Value("${mall.payment.refund-retry.batch-size:100}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${mall.payment.refund-retry.initial-delay-ms:60000}",
            fixedDelayString = "${mall.payment.refund-retry.interval-ms:300000}")
    @SchedulerLock(name = "paymentRefundRetryJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void retry() {
        try {
            int retried = paymentRefundService.retryFailedRefunds(batchSize);
            if (retried > 0) {
                log.info("退款重试任务完成，本次成功落定 {} 笔", retried);
            }
        } catch (Exception e) {
            // 任务层兜底，避免一次异常导致后续调度被取消
            log.error("退款重试任务执行失败", e);
        }
    }
}
