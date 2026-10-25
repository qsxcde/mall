package com.geekmall.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.inventory.entity.InvBucket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 库存桶 Mapper。
 *
 * <p>余量变更一律使用 CAS（{@code WHERE stock >= qty}），由数据库保证并发下不超卖；
 * 这与 {@code ProductMapper.deductStock} 的思路一致。</p>
 */
@Mapper
public interface InvBucketMapper extends BaseMapper<InvBucket> {

    /**
     * 桶内 CAS 扣减：只有当前余量足够时才成功，返回 0 表示余量不足（桶已空或并发被抢）。
     */
    @Update("UPDATE inv_bucket SET stock = stock - #{qty}, last_sync_time = NOW() "
            + "WHERE id = #{id} AND stock >= #{qty} AND deleted = 0")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 桶内回加（调拨入库 / 合并 / 回滚）。 */
    @Update("UPDATE inv_bucket SET stock = stock + #{qty}, last_sync_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int addStock(@Param("id") Long id, @Param("qty") int qty);

    /** 桶内回加并累加入桶总量（入桶 / 合并目标）。 */
    @Update("UPDATE inv_bucket SET stock = stock + #{qty}, total = total + #{qty}, last_sync_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int addStockAndTotal(@Param("id") Long id, @Param("qty") int qty);

    /** 商品全部桶的余量合计，用于与 pms_product.stock 对账。 */
    @Select("SELECT COALESCE(SUM(stock), 0) FROM inv_bucket WHERE product_id = #{productId} AND deleted = 0")
    int sumStockByProduct(@Param("productId") Long productId);

    /** 某 SKU 全部桶的余量合计，用于与 pms_sku.stock 对账。 */
    @Select("SELECT COALESCE(SUM(stock), 0) FROM inv_bucket WHERE sku_id = #{skuId} AND deleted = 0")
    int sumStockBySku(@Param("skuId") Long skuId);

    /** 出库候选桶：指定 SKU 下有余量且未冻结的桶，挑桶策略在应用层排序后决定先后。 */
    @Select("SELECT * FROM inv_bucket WHERE product_id = #{productId} AND sku_id = #{skuId} "
            + "AND stock > 0 AND status = 1 AND deleted = 0")
    List<InvBucket> selectDeductCandidates(@Param("productId") Long productId, @Param("skuId") Long skuId);

    /**
     * 批量读取多个商品的桶，供批量出库一次预加载，避免逐单 N 次 SELECT。
     */
    @Select(
            """
            <script>
            SELECT * FROM inv_bucket
            WHERE deleted = 0 AND product_id IN
            <foreach collection="productIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            ORDER BY product_id, id
            </script>
            """)
    List<InvBucket> selectByProductIds(@Param("productIds") Collection<Long> productIds);

    /** 商品按维度汇总的余量与桶数，用于分桶报告。 */
    @Select("SELECT dimension, COALESCE(SUM(stock), 0) AS stock, COUNT(1) AS bucketCount "
            + "FROM inv_bucket WHERE product_id = #{productId} AND deleted = 0 GROUP BY dimension")
    List<Map<String, Object>> sumGroupByDimension(@Param("productId") Long productId);

    /**
     * 对账巡检 · SKU 层：找出「桶余量合计 ≠ SKU 库存」的 SKU。
     *
     * <p>{@code sku_id = 0} 的历史桶（无规格商品）退化为与商品总库存对账，
     * 保持迁移前的守恒口径。正常情况下永远返回空集；非空即代表守恒被破坏
     * （并发缺陷、人工改库、回补失败等），由 {@code InventoryBucketReconcileJob} 定时告警。</p>
     */
    @Select("SELECT b.product_id AS productId, b.sku_id AS skuId, "
            + "COALESCE(s.stock, p.stock) AS expectedStock, SUM(b.stock) AS bucketStock "
            + "FROM inv_bucket b "
            + "JOIN pms_product p ON p.id = b.product_id AND p.deleted = 0 "
            + "LEFT JOIN pms_sku s ON s.id = b.sku_id AND s.deleted = 0 "
            + "WHERE b.deleted = 0 "
            + "GROUP BY b.product_id, b.sku_id, expectedStock "
            + "HAVING SUM(b.stock) <> expectedStock")
    List<Map<String, Object>> selectInconsistentSkus();

    /**
     * 对账巡检 · 商品层：找出「SUM(SKU 库存) ≠ 商品总库存」的商品。
     *
     * <p>只覆盖有 SKU 的商品；无 SKU 的历史商品由 {@link #selectInconsistentSkus} 兜底。</p>
     */
    @Select("SELECT s.product_id AS productId, p.stock AS productStock, SUM(s.stock) AS skuStock "
            + "FROM pms_sku s JOIN pms_product p ON p.id = s.product_id AND p.deleted = 0 "
            + "WHERE s.deleted = 0 "
            + "GROUP BY s.product_id, p.stock HAVING SUM(s.stock) <> p.stock")
    List<Map<String, Object>> selectInconsistentProducts();
}
