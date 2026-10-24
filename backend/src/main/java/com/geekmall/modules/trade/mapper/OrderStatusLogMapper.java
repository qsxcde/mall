package com.geekmall.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单状态日志 Mapper。
 */
@Mapper
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLog> {

    /** 批量插入初始状态日志（批量落库时一次写入 N 条）。 */
    @Insert(
            """
            <script>
            INSERT INTO oms_order_status_log
                (order_no, from_status, to_status, operator, remark)
            VALUES
            <foreach collection="logs" item="l" separator=",">
                (#{l.orderNo}, #{l.fromStatus}, #{l.toStatus}, #{l.operator}, #{l.remark})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("logs") List<OrderStatusLog> logs);
}
