package com.geekmall.modules.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.marketing.entity.SeckillItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀商品 Mapper。
 */
@Mapper
public interface SeckillItemMapper extends BaseMapper<SeckillItem> {

    /**
     * 扣减秒杀活动库存：带 {@code stock > 0} 条件，作为 Redis 预扣之后的第二道防线。
     * 返回 0 表示活动库存已耗尽。
     */
    @Update("UPDATE mkt_seckill_item SET stock = stock - 1, sold = sold + 1 "
            + "WHERE id = #{id} AND stock > 0 AND deleted = 0")
    int deductStock(@Param("id") Long id);

    /**
     * 按数量批量归并扣减：同一批次内同一活动商品的 N 单合并为一次 CAS。
     *
     * <p>只用于不分桶商品；分桶商品的扣减对象是 {@code mkt_seckill_bucket}，
     * {@code mkt_seckill_item.stock/sold} 自启用分桶起不再逐单更新。</p>
     */
    @Update("UPDATE mkt_seckill_item SET stock = stock - #{qty}, sold = sold + #{qty} "
            + "WHERE id = #{id} AND stock >= #{qty} AND deleted = 0")
    int deductStockByQty(@Param("id") Long id, @Param("qty") int qty);

    /** 回滚活动库存（下单失败补偿）。 */
    @Update("UPDATE mkt_seckill_item SET stock = stock + 1, sold = GREATEST(sold - 1, 0) WHERE id = #{id}")
    int restoreStock(@Param("id") Long id);
}
