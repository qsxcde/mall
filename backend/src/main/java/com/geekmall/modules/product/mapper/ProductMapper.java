package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper。复杂 SQL 可写在 resources/mapper/ProductMapper.xml。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 扣减库存：带 {@code stock >= qty} 条件，由数据库保证并发下不超卖。
     * 受影响行数为 0 代表库存不足。
     */
    @Update("UPDATE pms_product SET stock = stock - #{qty}, sales = sales + #{qty} "
            + "WHERE id = #{productId} AND stock >= #{qty} AND deleted = 0")
    int deductStock(@Param("productId") Long productId, @Param("qty") int qty);

    /** 回滚库存（订单取消 / 超时未支付）。 */
    @Update("UPDATE pms_product SET stock = stock + #{qty}, sales = GREATEST(sales - #{qty}, 0) "
            + "WHERE id = #{productId}")
    int restoreStock(@Param("productId") Long productId, @Param("qty") int qty);
}
