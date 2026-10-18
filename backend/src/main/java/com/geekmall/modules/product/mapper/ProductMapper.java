package com.geekmall.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.product.entity.Product;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 按 ID 批量查询「在售」商品（搜索命中后回表用）。
     *
     * <p>搜索引擎（FULLTEXT / ES）只负责「找出哪些 id 命中」，价格、库存、上下架状态
     * 这些强一致字段必须回 MySQL 取 —— 搜索索引有同步延迟，直接展示索引副本会出现
     * 「搜到了但点进去已下架」。</p>
     *
     * <p>{@code status = 1 AND deleted = 0} 放在这里而不是交给调用方：索引里可能残留
     * 刚下架的商品，回表时直接过滤掉比在 Java 层再筛一层更省事、也更安全。</p>
     */
    @Select(
            """
            <script>
            SELECT * FROM pms_product
            WHERE deleted = 0 AND status = 1
              AND id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Product> selectOnShelfByIds(@Param("ids") List<Long> ids);

    /**
     * FULLTEXT 搜索的总命中数。
     *
     * <p>{@code phrase} 必须是已加双引号的短语（如 {@code "折叠屏"}）——
     * ngram 分词下只有短语模式才与 {@code LIKE '%kw%'} 语义等价。</p>
     */
    @Select(
            """
            <script>
            SELECT COUNT(*) FROM pms_product
            WHERE deleted = 0 AND status = 1
              AND MATCH(title, brand_name, category_name) AGAINST(#{phrase} IN BOOLEAN MODE)
              <if test="sub != null and sub != ''">AND category_key = #{sub}</if>
              <if test="(sub == null or sub == '') and cat != null and cat != ''">AND parent_key = #{cat}</if>
              <if test="brands != null and brands.size() > 0">
                AND brand_name IN <foreach collection="brands" item="b" open="(" separator="," close=")">#{b}</foreach>
              </if>
              <if test="priceMin != null">AND price &gt;= #{priceMin}</if>
              <if test="priceMax != null">AND price &lt;= #{priceMax}</if>
              <if test="hot != null">AND is_hot = #{hot}</if>
              <if test="isNew != null">AND is_new = #{isNew}</if>
            </script>
            """)
    long countFulltext(
            @Param("phrase") String phrase,
            @Param("sub") String sub,
            @Param("cat") String cat,
            @Param("brands") List<String> brands,
            @Param("priceMin") java.math.BigDecimal priceMin,
            @Param("priceMax") java.math.BigDecimal priceMax,
            @Param("hot") Integer hot,
            @Param("isNew") Integer isNew);

    /**
     * FULLTEXT 搜索命中的商品 ID（按业务排序取一页）。
     *
     * <p>只返回 id，由调用方回表组装 —— 避免把整行宽表数据在 SQL 层多做一次投影。</p>
     */
    @Select(
            """
            <script>
            SELECT id FROM pms_product
            WHERE deleted = 0 AND status = 1
              AND MATCH(title, brand_name, category_name) AGAINST(#{phrase} IN BOOLEAN MODE)
              <if test="sub != null and sub != ''">AND category_key = #{sub}</if>
              <if test="(sub == null or sub == '') and cat != null and cat != ''">AND parent_key = #{cat}</if>
              <if test="brands != null and brands.size() > 0">
                AND brand_name IN <foreach collection="brands" item="b" open="(" separator="," close=")">#{b}</foreach>
              </if>
              <if test="priceMin != null">AND price &gt;= #{priceMin}</if>
              <if test="priceMax != null">AND price &lt;= #{priceMax}</if>
              <if test="hot != null">AND is_hot = #{hot}</if>
              <if test="isNew != null">AND is_new = #{isNew}</if>
              ORDER BY
              <choose>
                <when test="sort == 'sales'">sales DESC</when>
                <when test="sort == 'price_asc'">price ASC</when>
                <when test="sort == 'price_desc'">price DESC</when>
                <when test="sort == 'rating'">rating DESC</when>
                <when test="sort == 'new'">id DESC</when>
                <otherwise>is_hot DESC, sales DESC</otherwise>
              </choose>
              LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Long> searchFulltextIds(
            @Param("phrase") String phrase,
            @Param("sub") String sub,
            @Param("cat") String cat,
            @Param("brands") List<String> brands,
            @Param("priceMin") java.math.BigDecimal priceMin,
            @Param("priceMax") java.math.BigDecimal priceMax,
            @Param("hot") Integer hot,
            @Param("isNew") Integer isNew,
            @Param("sort") String sort,
            @Param("limit") long limit,
            @Param("offset") long offset);
}
