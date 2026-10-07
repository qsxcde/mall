package com.geekmall.modules.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.merchant.entity.Shop;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 店铺 Mapper。
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {

    /**
     * 条件扣减可用余额。
     *
     * <p>把「校验余额」和「扣减」合并成一条带条件的 UPDATE，由数据库保证原子性：
     * 余额不足时影响行数为 0，调用方据此拒绝提现。
     * 这是「先查余额再扣减」写法在并发下的必要替代 —— 后者会出现两个请求都读到足够余额、
     * 各自扣减成功，最终把余额提成负数。</p>
     *
     * @return 影响行数，0 表示余额不足
     */
    @Update("UPDATE mms_shop SET balance = balance - #{amount}, update_time = NOW() "
            + "WHERE id = #{shopId} AND deleted = 0 AND balance >= #{amount}")
    int deductBalance(@Param("shopId") Long shopId, @Param("amount") BigDecimal amount);
}
