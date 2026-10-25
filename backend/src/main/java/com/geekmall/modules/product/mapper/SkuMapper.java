package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.Sku;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 SKU Mapper。
 */
@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    /**
     * 按组合唯一键 upsert。
     *
     * <p>{@code pms_sku} 有两个唯一键（{@code uk_sku_code} 与 {@code uk_product_spec_keys}），
     * {@code sku_code} 由 {@code productId + 排序后的规格值 ID} 确定性生成，
     * 两个键永远指向同一行，因此 upsert 不会误改到别的行。</p>
     */
    @Insert("INSERT INTO pms_sku (product_id, shop_id, sku_code, spec_desc, spec_keys, "
            + "price, cost, stock, safe_stock, status) "
            + "VALUES (#{productId}, #{shopId}, #{skuCode}, #{specDesc}, #{specKeys}, "
            + "#{price}, #{cost}, #{stock}, #{safeStock}, 1) "
            + "ON DUPLICATE KEY UPDATE deleted = 0, shop_id = VALUES(shop_id), spec_desc = VALUES(spec_desc), "
            + "price = VALUES(price), cost = VALUES(cost), stock = VALUES(stock), status = 1")
    int upsert(
            @Param("productId") Long productId,
            @Param("shopId") Long shopId,
            @Param("skuCode") String skuCode,
            @Param("specDesc") String specDesc,
            @Param("specKeys") String specKeys,
            @Param("price") BigDecimal price,
            @Param("cost") BigDecimal cost,
            @Param("stock") int stock,
            @Param("safeStock") int safeStock);

    /** 覆盖式重建：整商品的旧 SKU 先退休（停售 + 逻辑删）。 */
    @Update("UPDATE pms_sku SET deleted = 1, status = 0 WHERE product_id = #{productId} AND deleted = 0")
    int retireByProduct(@Param("productId") Long productId);

    /**
     * SKU 扣减：一条语句同时扣 {@code pms_sku.stock} 与 {@code pms_product.stock}（含商品销量）。
     *
     * <p>两层放在同一句（多表 UPDATE 原子）而不是两条语句：即使调用方没开事务，
     * 也不会出现「SKU 扣了、商品没扣」的撕裂，商品层守恒恒成立。
     * 返回 0 表示库存不足。</p>
     */
    @Update("UPDATE pms_sku s JOIN pms_product p ON p.id = s.product_id "
            + "SET s.stock = s.stock - #{qty}, p.stock = p.stock - #{qty}, p.sales = p.sales + #{qty} "
            + "WHERE s.id = #{skuId} AND s.deleted = 0 AND p.deleted = 0 "
            + "AND s.stock >= #{qty} AND p.stock >= #{qty}")
    int deductStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /** SKU 回补：一条语句同时补两层库存并回落商品销量。 */
    @Update("UPDATE pms_sku s JOIN pms_product p ON p.id = s.product_id "
            + "SET s.stock = s.stock + #{qty}, p.stock = p.stock + #{qty}, p.sales = GREATEST(p.sales - #{qty}, 0) "
            + "WHERE s.id = #{skuId} AND s.deleted = 0 AND p.deleted = 0")
    int restoreStock(@Param("skuId") Long skuId, @Param("qty") int qty);
}
