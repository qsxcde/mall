package com.geekmall.modules.payment.job;

import com.geekmall.modules.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 「支付成功但订单未落账」的对账任务。
 *
 * <p>覆盖的故障窗口：支付单已 CAS 成成功，随后订单落账因数据库抖动/异常失败；
 * 渠道重试时又会因「支付单已成功」而直接应答 success，导致订单永远停在待支付，
 * 最终被超时任务取消。本任务按「支付成功 + 订单待支付/已关闭」精确扫描并修复。</p>
 *
 * <p>多实例部署时 {@link SchedulerLock} 保证同一时刻只有一个实例执行（Redis 实现）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.payment.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentOrderReconcileJob {

    private final PaymentService paymentService;

    /** 单轮处理条数，积压时可上调。 */
    @Value("${mall.payment.reconcile.batch-size:100}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${mall.payment.reconcile.initial-delay-ms:90000}",
            fixedDelayString = "${mall.payment.reconcile.interval-ms:60000}")
    @SchedulerLock(name = "paymentOrderReconcileJob", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void reconcile() {
        try {
            int reconciled = paymentService.reconcileSucceeded(batchSize);
            if (reconciled > 0) {
                log.info("支付对账任务完成，本次修正 {} 笔", reconciled);
            }
        } catch (Exception e) {
            // 任务层兜底，避免一次异常导致后续调度被取消
            log.error("支付对账任务执行失败", e);
        }
    }
}
