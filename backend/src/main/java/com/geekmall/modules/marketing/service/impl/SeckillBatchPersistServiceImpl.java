package com.geekmall.modules.marketing.service.impl;

import com.geekmall.modules.marketing.dto.BatchOrderOutcome;
import com.geekmall.modules.marketing.dto.BatchPersistResult;
import com.geekmall.modules.marketing.queue.SeckillOrderMessage;
import com.geekmall.modules.marketing.service.SeckillBatchPersistService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀批量落库编排实现（骨架版）。
 *
 * <p>本阶段先把「N 条消息放进同一个事务」这层链路跑通：事务内仍逐条走既有的
 * {@link SeckillServiceImpl#grabInTx}（扣活动库存 + 建单）。任一条失败即抛出，
 * 由消费端整批回滚后退回逐条路径 —— 批量此时只是「一次事务包裹」，
 * 真正的批量写入与库存归并在后续提交里补齐。</p>
 *
 * <p><b>Redis 不在本事务内</b>：预扣回补、结果写入都由消费端在事务之外完成，
 * 避免「DB 已回滚但 Redis 回补也被回滚」的错位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillBatchPersistServiceImpl implements SeckillBatchPersistService {

    private final SeckillServiceImpl seckillService;

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 15)
    public BatchPersistResult persist(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new BatchPersistResult(List.of());
        }
        Map<String, SeckillOrderMessage> unique = dedupe(messages);
        List<BatchOrderOutcome> outcomes = new ArrayList<>(unique.size());
        for (SeckillOrderMessage message : unique.values()) {
            // 逐条建单：任一条抛异常都会让本事务整体回滚，交由消费端逐条降级处理
            String orderNo = seckillService.grabInTx(
                    message.userId(), message.itemId(), message.addressId(), message.requestId(), message.bucketNo());
            outcomes.add(BatchOrderOutcome.success(message.requestId(), message.userId(), orderNo));
        }
        log.info("[秒杀批量] 批量落库完成（骨架版，事务内逐条建单）：消息数={}", unique.size());
        return new BatchPersistResult(outcomes);
    }

    private Map<String, SeckillOrderMessage> dedupe(List<SeckillOrderMessage> messages) {
        Map<String, SeckillOrderMessage> unique = new LinkedHashMap<>();
        for (SeckillOrderMessage message : messages) {
            if (message != null && message.requestId() != null) {
                unique.putIfAbsent(message.requestId(), message);
            }
        }
        return unique;
    }
}
