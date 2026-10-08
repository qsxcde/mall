package com.geekmall.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.inventory.entity.InvBucketLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
    int countByOrderProduct(@Param("bizType") String bizType,
                            @Param("orderNo") String orderNo,
                            @Param("productId") Long productId);

    /** 取某出库单在某商品各桶上的出库流水，用于「把数量还回原来那一桶」。 */
    @Select("SELECT * FROM inv_bucket_log WHERE biz_type = 'OUTBOUND' "
            + "AND order_no = #{orderNo} AND product_id = #{productId}")
    List<InvBucketLog> selectOutboundByOrderProduct(@Param("orderNo") String orderNo,
                                                    @Param("productId") Long productId);

    /** 取某出库单的全部出库流水（可能跨多个商品）。 */
    @Select("SELECT * FROM inv_bucket_log WHERE biz_type = 'OUTBOUND' AND order_no = #{orderNo}")
    List<InvBucketLog> selectOutboundByOrder(@Param("orderNo") String orderNo);
}
