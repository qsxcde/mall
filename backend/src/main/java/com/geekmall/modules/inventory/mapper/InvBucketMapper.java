package com.geekmall.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.inventory.entity.InvBucket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

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

    /** 出库候选桶：有余量且未冻结，挑桶策略在应用层排序后决定先后。 */
    @Select("SELECT * FROM inv_bucket WHERE product_id = #{productId} "
            + "AND stock > 0 AND status = 1 AND deleted = 0")
    List<InvBucket> selectDeductCandidates(@Param("productId") Long productId);

    /** 商品按维度汇总的余量与桶数，用于分桶报告。 */
    @Select("SELECT dimension, COALESCE(SUM(stock), 0) AS stock, COUNT(1) AS bucketCount "
            + "FROM inv_bucket WHERE product_id = #{productId} AND deleted = 0 GROUP BY dimension")
    List<Map<String, Object>> sumGroupByDimension(@Param("productId") Long productId);

    /**
     * 对账巡检：找出「桶余量合计 ≠ 商品总库存」的商品。
     *
     * <p>正常情况下永远返回空集；非空即代表守恒被破坏（并发缺陷、人工改库、回补失败等），
     * 由 {@code InventoryBucketReconcileJob} 定时扫描并告警。</p>
     */
    @Select("SELECT b.product_id AS productId, p.stock AS productStock, SUM(b.stock) AS bucketStock "
            + "FROM inv_bucket b JOIN pms_product p ON p.id = b.product_id "
            + "WHERE b.deleted = 0 AND p.deleted = 0 "
            + "GROUP BY b.product_id, p.stock HAVING SUM(b.stock) <> p.stock")
    List<Map<String, Object>> selectInconsistentProducts();
}
