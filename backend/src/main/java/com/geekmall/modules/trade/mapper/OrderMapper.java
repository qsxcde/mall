package com.geekmall.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geekmall.modules.trade.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

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
}
