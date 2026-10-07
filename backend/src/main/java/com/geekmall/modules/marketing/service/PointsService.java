package com.geekmall.modules.marketing.service;

import com.geekmall.modules.marketing.vo.PointsExchangeVO;
import com.geekmall.modules.marketing.vo.PointsGoodsVO;

import java.util.List;

/**
 * 积分商城服务。
 */
public interface PointsService {

    /** 积分商品列表，带「积分是否足够」标记。 */
    List<PointsGoodsVO> goods(Long userId);

    /**
     * 积分兑换：扣积分 + 扣库存 + 写兑换记录，三步同一事务。
     *
     * @return 兑换结果（含剩余积分）
     */
    PointsExchangeVO exchange(Long userId, Long goodsId);

    /** 我的兑换记录。 */
    List<PointsExchangeVO> records(Long userId);
}
