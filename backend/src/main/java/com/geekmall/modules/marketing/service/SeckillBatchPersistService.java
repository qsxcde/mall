package com.geekmall.modules.marketing.service;

import com.geekmall.modules.marketing.dto.BatchPersistResult;
import com.geekmall.modules.marketing.queue.SeckillOrderMessage;
import java.util.List;

/**
 * 秒杀批量落库编排。
 *
 * <p>一次事务内完成：幂等预查 → 活动库存归并扣减 → 订单 / 明细 / 状态日志批量写入
 * → 商品库存归并扣减。任一步系统异常都整体回滚，由消费端回退到逐条路径。</p>
 */
public interface SeckillBatchPersistService {

    /** 批量落库；返回每条消息的业务结果。 */
    BatchPersistResult persist(List<SeckillOrderMessage> messages);
}
