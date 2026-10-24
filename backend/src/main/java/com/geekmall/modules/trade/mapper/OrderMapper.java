package com.geekmall.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.trade.dto.OrderRequestPair;
import com.geekmall.modules.trade.entity.Order;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单 Mapper。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 一次 GROUP BY 取回某用户的各状态订单数（P1-6）。
     *
     * <p>替代原来「5 次串行 COUNT」的实现，订单页状态概览由 5 条 SQL 降为 1 条。</p>
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM oms_order "
            + "WHERE user_id = #{userId} AND deleted = 0 GROUP BY status")
    List<Map<String, Object>> countGroupByStatus(@Param("userId") Long userId);

    /**
     * 批量插入订单主表。
     *
     * <p>一条多值 INSERT 替代 N 条单行 INSERT；{@code create_time}/{@code update_time}
     * 由数据库默认值填充，与单条插入的行为一致。</p>
     */
    @Insert(
            """
            <script>
            INSERT INTO oms_order
                (order_no, user_id, status, goods_amount, shipping_fee, discount, pay_amount,
                 coupon_id, request_id, address_snap, remark, pay_method, expire_time, deleted)
            VALUES
            <foreach collection="orders" item="o" separator=",">
                (#{o.orderNo}, #{o.userId}, #{o.status}, #{o.goodsAmount}, #{o.shippingFee},
                 #{o.discount}, #{o.payAmount}, #{o.couponId}, #{o.requestId}, #{o.addressSnap},
                 #{o.remark}, #{o.payMethod}, #{o.expireTime}, 0)
            </foreach>
            </script>
            """)
    int batchInsert(@Param("orders") List<Order> orders);

    /**
     * 按订单号批量回查自增 ID。
     *
     * <p>批量 INSERT 无法稳定拿回所有 generated key，因此插入后用
     * {@code uk_order_no} 一次查回，供订单明细回填 {@code order_id}。</p>
     */
    @Select(
            """
            <script>
            SELECT id, order_no, user_id, request_id FROM oms_order
            WHERE order_no IN
            <foreach collection="orderNos" item="no" open="(" separator="," close=")">#{no}</foreach>
            </script>
            """)
    List<Order> selectByOrderNos(@Param("orderNos") Collection<String> orderNos);

    /**
     * 按 {@code (user_id, request_id)} 元组批量预查已建订单。
     *
     * <p>走唯一索引 {@code uk_user_request}，一次查回一批消息的幂等结果，
     * 避免批量落库时逐条 SELECT。</p>
     */
    @Select(
            """
            <script>
            SELECT id, order_no, user_id, request_id FROM oms_order
            WHERE (user_id, request_id) IN
            <foreach collection="pairs" item="p" open="(" separator="," close=")">
                (#{p.userId}, #{p.requestId})
            </foreach>
              AND deleted = 0
            </script>
            """)
    List<Order> selectByUserRequestPairs(@Param("pairs") List<OrderRequestPair> pairs);
}
