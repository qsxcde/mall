package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.SpecValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品规格值 Mapper。
 */
@Mapper
public interface SpecValueMapper extends BaseMapper<SpecValue> {

    /**
     * 按自然键 {@code (dimension_id, value)} upsert（复活语义见
     * {@link SpecDimensionMapper#upsert}）。
     */
    @Insert("INSERT INTO pms_spec_value (dimension_id, product_id, value, image, sort) "
            + "VALUES (#{dimensionId}, #{productId}, #{value}, #{image}, #{sort}) "
            + "ON DUPLICATE KEY UPDATE deleted = 0, product_id = VALUES(product_id), "
            + "image = VALUES(image), sort = VALUES(sort)")
    int upsert(
            @Param("dimensionId") Long dimensionId,
            @Param("productId") Long productId,
            @Param("value") String value,
            @Param("image") String image,
            @Param("sort") int sort);

    /** 覆盖式重建：整商品的旧取值先退休。 */
    @Update("UPDATE pms_spec_value SET deleted = 1 WHERE product_id = #{productId} AND deleted = 0")
    int retireByProduct(@Param("productId") Long productId);
}
