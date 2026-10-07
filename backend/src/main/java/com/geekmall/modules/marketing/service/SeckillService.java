package com.geekmall.modules.marketing.service;

import com.geekmall.modules.marketing.vo.SeckillItemVO;
import com.geekmall.modules.marketing.vo.SeckillSessionVO;

import java.util.List;

/**
 * 秒杀服务。
 */
public interface SeckillService {

    /** 场次列表（含未开始 / 进行中 / 已结束）。 */
    List<SeckillSessionVO> sessions();

    /** 某场次的秒杀商品。 */
    List<SeckillItemVO> items(Long sessionId);

    /**
     * 秒杀抢购。
     *
     * <p>流程：Redis Lua 原子预扣（判库存 + 一人一单）→ DB 扣活动库存 → 交易域按秒杀价建单；
     * 后两步任一失败都会回补 Redis 预扣，避免库存被「黑洞」吞掉。</p>
     *
     * @return 订单号
     */
    String grab(Long userId, Long itemId, Long addressId);
}
