package com.geekmall.modules.merchant.service;

import com.geekmall.modules.merchant.vo.NavBadgesVO;

import java.util.List;
import java.util.Map;

/**
 * 商家经营概览 / 数据看板服务。
 */
public interface MerchantDashboardService {

    /** 侧栏导航角标。 */
    NavBadgesVO navBadges();

    /** 经营概览。 */
    Map<String, Object> overview();

    /** 数据看板。 */
    Map<String, Object> analytics();

    /** 近 N 天日序列。 */
    List<Map<String, Object>> dailySeries(int days);

    /** 库存预警商品。 */
    List<Map<String, Object>> stockAlerts();
}
