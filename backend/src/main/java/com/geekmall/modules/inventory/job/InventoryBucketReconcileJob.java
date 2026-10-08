package com.geekmall.modules.inventory.job;

import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 库存分桶对账巡检任务。
 *
 * <p>守恒不变量 {@code SUM(inv_bucket.stock) == pms_product.stock} 由应用层在同一个事务里维护，
 * 正常永远不会破坏。本任务只做**兜底发现**：定时扫描差额非 0 的商品并告警，
 * 覆盖并发缺陷、人工改库、回补失败等异常路径。</p>
 *
 * <p>多实例用 {@code @SchedulerLock}（Redis）保证同时只有一个实例执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryBucketReconcileJob {

    private final InvBucketMapper bucketMapper;

    @Scheduled(initialDelayString = "${mall.inventory.reconcile-initial-delay-ms:60000}",
            fixedDelayString = "${mall.inventory.reconcile-ms:600000}")
    @SchedulerLock(name = "inventoryBucketReconcileJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void reconcile() {
        try {
            List<Map<String, Object>> inconsistent = bucketMapper.selectInconsistentProducts();
            if (inconsistent.isEmpty()) {
                log.debug("库存分桶对账巡检通过：无差额商品");
                return;
            }
            log.warn("库存分桶对账发现 {} 个商品「桶合计 ≠ 商品总库存」，需人工核查", inconsistent.size());
            for (Map<String, Object> row : inconsistent) {
                log.warn("库存分桶不一致：productId={}, 商品库存={}, 桶合计={}",
                        row.get("productId"), row.get("productStock"), row.get("bucketStock"));
            }
        } catch (Exception e) {
            // 任务层兜底，避免一次异常导致后续调度被取消
            log.error("库存分桶对账巡检执行失败", e);
        }
    }
}
