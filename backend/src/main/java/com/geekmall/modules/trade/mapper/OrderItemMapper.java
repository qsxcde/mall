package com.geekmall.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.trade.entity.OrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单明细 Mapper。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 批量插入订单明细（P2-4）。
     *
     * <p>原先循环调用 {@code insert} 会让 JDBC 的 {@code rewriteBatchedStatements=true} 完全失效，
     * 这里改写成一条多值 INSERT，N 条明细只发一条 SQL。</p>
     */
    @Insert("""
            <script>
            INSERT INTO oms_order_item
                (order_id, order_no, product_id, title, cover, spec, price, qty)
            VALUES
            <foreach collection="items" item="i" separator=",">
                (#{i.orderId}, #{i.orderNo}, #{i.productId}, #{i.title},
                 #{i.cover}, #{i.spec}, #{i.price}, #{i.qty})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("items") List<OrderItem> items);
}
