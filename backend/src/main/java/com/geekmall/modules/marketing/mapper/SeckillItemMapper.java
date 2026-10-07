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

    /** 回滚活动库存（下单失败补偿）。 */
    @Update("UPDATE mkt_seckill_item SET stock = stock + 1, sold = GREATEST(sold - 1, 0) WHERE id = #{id}")
    int restoreStock(@Param("id") Long id);
}
