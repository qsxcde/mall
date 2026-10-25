package com.geekmall.modules.inventory.job;

import com.geekmall.modules.inventory.mapper.InvBucketMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 库存分桶对账巡检任务。
 *
 * <p>守恒不变量（{@code SUM(inv_bucket.stock WHERE sku_id=X) == pms_sku.stock}、
 * {@code SUM(pms_sku.stock WHERE product_id=P) == pms_product.stock}）由应用层在同一个事务里维护，
 * 正常永远不会破坏。本任务只做**兜底发现**：两层各扫一遍差额非 0 的记录并告警，
 * 覆盖并发缺陷、人工改库、回补失败等异常路径 —— 只查一层会漏账
 * （例如 SKU 层都对、商品层汇总被改坏）。</p>
 *
 * <p>多实例用 {@code @SchedulerLock}（Redis）保证同时只有一个实例执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryBucketReconcileJob {

    private final InvBucketMapper bucketMapper;

    @Scheduled(
            initialDelayString = "${mall.inventory.reconcile-initial-delay-ms:60000}",
            fixedDelayString = "${mall.inventory.reconcile-ms:600000}")
    @SchedulerLock(name = "inventoryBucketReconcileJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void reconcile() {
        try {
            boolean clean = true;
            // SKU 层：桶合计 vs SKU 库存（sku_id=0 的历史商品退化为 vs 商品总库存）
            List<Map<String, Object>> inconsistentSkus = bucketMapper.selectInconsistentSkus();
            if (!inconsistentSkus.isEmpty()) {
                clean = false;
                log.warn("库存对账发现 {} 个 SKU「桶合计 ≠ SKU 库存」，需人工核查", inconsistentSkus.size());
                for (Map<String, Object> row : inconsistentSkus) {
                    log.warn(
                            "SKU 层不一致：productId={}, skuId={}, 应为={}, 桶合计={}",
                            row.get("productId"),
                            row.get("skuId"),
                            row.get("expectedStock"),
                            row.get("bucketStock"));
                }
            }
            // 商品层：SKU 库存合计 vs 商品总库存
            List<Map<String, Object>> inconsistentProducts = bucketMapper.selectInconsistentProducts();
            if (!inconsistentProducts.isEmpty()) {
                clean = false;
                log.warn("库存对账发现 {} 个商品「SKU 库存合计 ≠ 商品总库存」，需人工核查", inconsistentProducts.size());
                for (Map<String, Object> row : inconsistentProducts) {
                    log.warn(
                            "商品层不一致：productId={}, 商品库存={}, SKU 合计={}",
                            row.get("productId"),
                            row.get("productStock"),
                            row.get("skuStock"));
                }
            }
            if (clean) {
                log.debug("库存对账巡检通过：两层均无差额");
            }
        } catch (Exception e) {
            // 任务层兜底，避免一次异常导致后续调度被取消
            log.error("库存对账巡检执行失败", e);
        }
    }
}
