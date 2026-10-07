package com.geekmall.modules.marketing.service;

import com.geekmall.modules.marketing.queue.SeckillGrabResult;
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
     * 同步抢购（默认模式）。
     *
     * <p>流程：Redis Lua 原子预扣（判库存 + 一人一单）→ DB 扣活动库存 → 交易域按秒杀价建单；
     * 后两步任一失败都会回补 Redis 预扣，避免库存被「黑洞」吞掉。</p>
     *
     * @return 订单号
     */
    String grab(Long userId, Long itemId, Long addressId);

    /**
     * 异步抢购（削峰模式）。
     *
     * <p>预扣成功即入队并立即返回，落库由后台消费者完成。用户延迟从「DB 事务耗时」
     * 压缩到「预扣 + 入队耗时」，但代价是结果需要轮询获取。</p>
     *
     * @return 抢购请求号，前端凭它轮询 {@link #grabResult}
     */
    String grabAsync(Long userId, Long itemId, Long addressId);

    /**
     * 查询异步抢购结果。
     *
     * <p>越权（requestId 不属于当前用户）或已过期一律按「不存在」处理，
     * 不向调用方泄露该 requestId 是否存在。</p>
     */
    SeckillGrabResult grabResult(Long userId, String requestId);
}
