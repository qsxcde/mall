package com.geekmall.modules.marketing.mapper;

import com.geekmall.modules.marketing.entity.SeckillBucket;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 秒杀库存桶 Mapper。
 *
 * <p>不继承 {@code BaseMapper}：本表主键是 {@code (item_id, bucket_no)} 复合键，
 * 按 id 的 CRUD 没有意义，全部读写都用显式 SQL 表达，避免误用 {@code selectById}。</p>
 *
 * <p>余量变更一律使用 CAS（{@code WHERE stock > 0}），由数据库保证并发下不超卖 ——
 * 这与 {@code SeckillItemMapper.deductStock} 的思路一致。</p>
 */
@Mapper
public interface SeckillBucketMapper {

    /**
     * 桶内 CAS 扣减：返回 0 表示该桶已空（或并发被抢）。
     *
     * <p>{@code WHERE} 走主键等值定位，只锁命中的那一行 —— 这是分桶 SQL 的最理想形态
     * （唯一索引等值命中只加行锁，不加间隙锁）。</p>
     */
    @Update("UPDATE mkt_seckill_bucket SET stock = stock - 1 "
            + "WHERE item_id = #{itemId} AND bucket_no = #{bucketNo} AND stock > 0")
    int deductStock(@Param("itemId") Long itemId, @Param("bucketNo") int bucketNo);

    /** 某活动商品的全部桶，按桶号升序。 */
    @Select("SELECT * FROM mkt_seckill_bucket WHERE item_id = #{itemId} ORDER BY bucket_no")
    List<SeckillBucket> selectByItem(@Param("itemId") Long itemId);

    /** 批量读取多个活动商品的桶，用于列表页按 SUM(桶) 聚合展示。 */
    @Select("<script>SELECT * FROM mkt_seckill_bucket WHERE item_id IN "
            + "<foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY item_id, bucket_no</script>")
    List<SeckillBucket> selectByItems(@Param("itemIds") Collection<Long> itemIds);

    /**
     * 建桶：只在完全没有桶行时调用。并发初始化由主键冲突兜底（调用方捕获后重读）。
     */
    @Insert("<script>INSERT INTO mkt_seckill_bucket (item_id, bucket_no, stock, total) VALUES "
            + "<foreach collection='buckets' item='b' separator=','>"
            + "(#{b.itemId}, #{b.bucketNo}, #{b.stock}, #{b.total})"
            + "</foreach></script>")
    int insertBatch(@Param("buckets") List<SeckillBucket> buckets);

    /** 某活动商品各桶余量合计（绝对值），用于与 {@code mkt_seckill_item.stock} 对账。 */
    @Select("SELECT COALESCE(SUM(stock), 0) FROM mkt_seckill_bucket WHERE item_id = #{itemId}")
    int sumStockByItem(@Param("itemId") Long itemId);
}
