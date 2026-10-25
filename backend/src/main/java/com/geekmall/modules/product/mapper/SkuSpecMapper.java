package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.SkuSpec;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * SKU 规格值关联 Mapper。
 */
@Mapper
public interface SkuSpecMapper extends BaseMapper<SkuSpec> {

    /**
     * 按自然键 {@code (sku_id, spec_value_id)} upsert（复活语义见
     * {@link SpecDimensionMapper#upsert}）。
     */
    @Insert("INSERT INTO pms_sku_spec (sku_id, product_id, spec_value_id, dimension_id, value_snapshot) "
            + "VALUES (#{skuId}, #{productId}, #{specValueId}, #{dimensionId}, #{valueSnapshot}) "
            + "ON DUPLICATE KEY UPDATE deleted = 0, product_id = VALUES(product_id), "
            + "dimension_id = VALUES(dimension_id), value_snapshot = VALUES(value_snapshot)")
    int upsert(
            @Param("skuId") Long skuId,
            @Param("productId") Long productId,
            @Param("specValueId") Long specValueId,
            @Param("dimensionId") Long dimensionId,
            @Param("valueSnapshot") String valueSnapshot);

    /** 覆盖式重建：整商品的旧关联先退休。 */
    @Update("UPDATE pms_sku_spec SET deleted = 1 WHERE product_id = #{productId} AND deleted = 0")
    int retireByProduct(@Param("productId") Long productId);
}
