package com.geekmall.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.inventory.entity.InvBucketLog;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 库存桶审计流水 Mapper。
 */
@Mapper
public interface InvBucketLogMapper extends BaseMapper<InvBucketLog> {

    /**
     * 某单号 + 某商品下某类流水是否已存在（按「单号 + 商品」的幂等判据）。
     *
     * <p>用商品维度而非整单维度，是因为一张订单可能含多个商品，
     * 每个商品的分桶出库 / 回滚必须能独立判幂等。</p>
     */
    @Select("SELECT COUNT(1) FROM inv_bucket_log WHERE biz_type = #{bizType} "
            + "AND order_no = #{orderNo} AND product_id = #{productId}")
    int countByOrderProduct(
            @Param("bizType") String bizType, @Param("orderNo") String orderNo, @Param("productId") Long productId);

    /** 取某出库单在某商品各桶上的出库流水，用于「把数量还回原来那一桶」。 */
    @Select("SELECT * FROM inv_bucket_log WHERE biz_type = 'OUTBOUND' "
            + "AND order_no = #{orderNo} AND product_id = #{productId}")
    List<InvBucketLog> selectOutboundByOrderProduct(
            @Param("orderNo") String orderNo, @Param("productId") Long productId);

    /** 取某出库单的全部出库流水（可能跨多个商品）。 */
    @Select("SELECT * FROM inv_bucket_log WHERE biz_type = 'OUTBOUND' AND order_no = #{orderNo}")
    List<InvBucketLog> selectOutboundByOrder(@Param("orderNo") String orderNo);

    /**
     * 批量查询一批单号下已存在的出库流水，返回 {@code orderNo:productId} 形式。
     *
     * <p>批量出库在扣减前一次性判断「哪些单已经出过库」，避免逐单 COUNT；
     * 返回值做成字符串是为了不再引入一个仅用于查询的 DTO。</p>
     */
    @Select(
            """
            <script>
            SELECT CONCAT(order_no, ':', product_id) FROM inv_bucket_log
            WHERE biz_type = 'OUTBOUND' AND order_no IN
            <foreach collection="orderNos" item="no" open="(" separator="," close=")">#{no}</foreach>
            </script>
            """)
    List<String> selectOutboundKeys(@Param("orderNos") Collection<String> orderNos);

    /**
     * 批量写入出库流水（批量落库时一次写入 N 条）。
     *
     * <p>唯一索引 {@code uk_biz_idempotent} 仍是最终兜底：即使调用方漏判，
     * 重复插入也会被数据库拦下。</p>
     */
    @Insert(
            """
            <script>
            INSERT INTO inv_bucket_log
                (bucket_id, product_id, shop_id, biz_type, change_qty, before_stock, after_stock,
                 order_no, biz_id, bucket_key, operator, remark)
            VALUES
            <foreach collection="logs" item="l" separator=",">
                (#{l.bucketId}, #{l.productId}, #{l.shopId}, #{l.bizType}, #{l.changeQty},
                 #{l.beforeStock}, #{l.afterStock}, #{l.orderNo}, #{l.bizId}, #{l.bucketKey},
                 #{l.operator}, #{l.remark})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("logs") List<InvBucketLog> logs);
}
