package com.geekmall.modules.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.marketing.entity.PointsGoods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 积分商品 Mapper。
 */
@Mapper
public interface PointsGoodsMapper extends BaseMapper<PointsGoods> {

    /** 扣减库存：带 {@code stock > 0} 条件，返回 0 表示已兑完。 */
    @Update("UPDATE mkt_points_goods SET stock = stock - 1 WHERE id = #{id} AND stock > 0 AND deleted = 0")
    int deductStock(@Param("id") Long id);
}
