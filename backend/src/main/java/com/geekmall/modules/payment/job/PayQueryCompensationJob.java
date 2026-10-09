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
 * 支付「主动查单」补偿任务。
 *
 * <p><b>为什么需要它</b>：渠道的异步通知会丢 —— 网络抖动、回调地址不可达、
 * 服务重启、通知重试耗尽都会让「用户已付款，但系统还是待支付」。
 * 支付宝支持用 {@code alipay.trade.query} 主动查真实状态，因此用定时任务兜底对账。</p>
 *
 * <p>多实例部署时 {@link SchedulerLock} 保证同一时刻只有一个实例执行（Redis 实现）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.payment.compensation.enabled", havingValue = "true", matchIfMissing = true)
public class PayQueryCompensationJob {

    private final PaymentService paymentService;

    /** 单轮处理条数，积压时可上调。 */
    @Value("${mall.payment.compensation.batch-size:100}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${mall.payment.compensation.initial-delay-ms:60000}",
            fixedDelayString = "${mall.payment.compensation.interval-ms:60000}")
    @SchedulerLock(name = "payQueryCompensationJob", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void compensate() {
        try {
            int changed = paymentService.compensatePending(batchSize);
            if (changed > 0) {
                log.info("支付主动查单补偿完成，本次修正 {} 笔", changed);
            }
        } catch (Exception e) {
            // 任务层兜底，避免一次异常导致后续调度被取消
            log.error("支付主动查单补偿任务执行失败", e);
        }
    }
}
