package com.geekmall.modules.trade.service;

import com.geekmall.common.result.PageResult;
import com.geekmall.modules.trade.dto.OrderQueryDTO;
import com.geekmall.modules.trade.vo.LogisticsVO;
import com.geekmall.modules.trade.vo.OrderDetailVO;
import com.geekmall.modules.trade.vo.OrderStatusCountVO;
import com.geekmall.modules.trade.vo.OrderVO;

/**
 * 订单查询服务。
 */
public interface OrderService {

    /** 我的订单：状态筛选 + 关键词搜索 + 分页。 */
    PageResult<OrderVO> page(Long userId, OrderQueryDTO query);

    /** 各状态订单计数。 */
    OrderStatusCountVO statusCounts(Long userId);

    /** 订单详情（含进度时间轴、物流摘要）。 */
    OrderDetailVO detail(Long userId, String orderNo);

    /** 物流跟踪。 */
    LogisticsVO logistics(Long userId, String orderNo);

    /** 全站累计订单数（供内容域统计展示）。 */
    long countAllOrders();

    /**
     * 批量取订单首件商品标题，供售后列表等场景展示 —— 一次查询解决，避免 N+1。
     */
    java.util.Map<String, String> firstItemTitles(java.util.Collection<String> orderNos);
}
