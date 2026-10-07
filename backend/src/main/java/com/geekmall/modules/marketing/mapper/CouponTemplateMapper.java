package com.geekmall.modules.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.marketing.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 优惠券模板 Mapper。
 */
@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 领取时扣减库存：带 {@code stock > 0} 条件，由数据库保证并发下不超发。
     * 返回 0 表示已被抢光。
     */
    @Update("UPDATE mkt_coupon_template SET stock = stock - 1 "
            + "WHERE id = #{id} AND stock > 0 AND status = 1 AND deleted = 0")
    int deductStock(@Param("id") Long id);

    /** 重算已领百分比，用于前端进度条展示。 */
    @Update("UPDATE mkt_coupon_template "
            + "SET percent = LEAST(100, ROUND((total - stock) * 100 / GREATEST(total, 1))) "
            + "WHERE id = #{id}")
    int refreshPercent(@Param("id") Long id);
}
