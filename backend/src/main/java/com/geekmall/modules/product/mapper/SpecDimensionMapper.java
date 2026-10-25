package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.SpecDimension;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品规格维度 Mapper。
 */
@Mapper
public interface SpecDimensionMapper extends BaseMapper<SpecDimension> {

    /**
     * 按自然键 {@code (product_id, name)} upsert。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是「先删后插」：逻辑删除的行仍占用唯一键，
     * 纯插入会在「删掉某维度后又加回」时撞唯一索引；upsert 同时把旧行复活（{@code deleted = 0}）。</p>
     */
    @Insert("INSERT INTO pms_spec_dimension (product_id, shop_id, name, sort) "
            + "VALUES (#{productId}, #{shopId}, #{name}, #{sort}) "
            + "ON DUPLICATE KEY UPDATE deleted = 0, shop_id = VALUES(shop_id), sort = VALUES(sort)")
    int upsert(
            @Param("productId") Long productId,
            @Param("shopId") Long shopId,
            @Param("name") String name,
            @Param("sort") int sort);

    /** 覆盖式重建的第一步：整商品的旧维度先退休，随后 upsert 会复活仍在用的行。 */
    @Update("UPDATE pms_spec_dimension SET deleted = 1 WHERE product_id = #{productId} AND deleted = 0")
    int retireByProduct(@Param("productId") Long productId);
}
