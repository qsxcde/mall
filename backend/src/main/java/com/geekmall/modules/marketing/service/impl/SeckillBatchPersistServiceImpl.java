package com.geekmall.modules.marketing.service.impl;

import com.geekmall.modules.marketing.bucket.SeckillBucketManager;
import com.geekmall.modules.marketing.dto.BatchOrderOutcome;
import com.geekmall.modules.marketing.dto.BatchPersistResult;
import com.geekmall.modules.marketing.entity.SeckillItem;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.queue.SeckillOrderMessage;
import com.geekmall.modules.marketing.service.SeckillBatchPersistService;
import com.geekmall.modules.marketing.support.SeckillStockBatchDeductor;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.trade.dto.FixedPriceOrderCommand;
import com.geekmall.modules.trade.dto.OrderRequestPair;
import com.geekmall.modules.trade.service.TradeService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀批量落库编排实现。
 *
 * <p>执行顺序（与单条路径的锁顺序一致）：</p>
 * <ol>
 *   <li>批量预查已建订单，命中幂等键的直接返回原订单号；</li>
 *   <li>活动库存按 {@code itemId + bucketNo} 归并扣减（短锁）；</li>
 *   <li>交易域批量插入订单 / 明细 / 状态日志，最后归并扣减商品库存；</li>
 *   <li>任一步系统异常 → 整个事务回滚，调用方逐条重试。</li>
 * </ol>
 *
 * <p><b>Redis 不在本事务内</b>：预扣回补、结果写入都由消费端在事务之外完成，
 * 避免「DB 已回滚但 Redis 回补也被回滚」的错位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillBatchPersistServiceImpl implements SeckillBatchPersistService {

    private static final String SECKILL_REMARK = "限时秒杀成交";

    private final SeckillItemMapper seckillItemMapper;
    private final SeckillBucketManager bucketManager;
    private final SeckillStockBatchDeductor stockDeductor;
    private final ProductStockService productStockService;
    private final TradeService tradeService;

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 15)
    public BatchPersistResult persist(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new BatchPersistResult(List.of());
        }
        Map<String, SeckillOrderMessage> unique = dedupe(messages);
        Map<Long, SeckillItem> items = loadItems(unique.values());
        Map<Long, Product> products = loadProducts(items.values());

        List<BatchOrderOutcome> outcomes = new ArrayList<>(unique.size());
        List<SeckillStockBatchDeductor.Deduction> deductions = new ArrayList<>(unique.size());
        List<FixedPriceOrderCommand> commands = new ArrayList<>(unique.size());

        for (SeckillOrderMessage message : unique.values()) {
            SeckillItem item = items.get(message.itemId());
            String invalid = validate(message, item);
            if (invalid != null) {
                outcomes.add(BatchOrderOutcome.failed(message.requestId(), message.userId(), invalid));
                continue;
            }
            boolean bucketed = bucketManager.isBucketed(item);
            if (bucketed && message.bucketNo() == null) {
                // 旧消息（分桶上线前入队）没有桶号：扣单行会把分桶商品的库存算错，只能判失败
                outcomes.add(BatchOrderOutcome.failed(message.requestId(), message.userId(), "库存桶信息缺失，请重新抢购"));
                continue;
            }
            if (!bucketed && message.bucketNo() != null) {
                outcomes.add(BatchOrderOutcome.failed(message.requestId(), message.userId(), "库存模式已变更，请重新抢购"));
                continue;
            }
            // 商品已下架 / 不存在：活动期常见的一条脏消息。若放进批量，
            // 交易域的校验会抛异常把整个事务回滚，同批的正常请求被迫退回逐条重试。
            // 因此这里先按业务失败摘出去，让批量只处理确定能落库的消息。
            Product product = products.get(item.getProductId());
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                outcomes.add(BatchOrderOutcome.failed(message.requestId(), message.userId(), "商品不存在或已下架"));
                continue;
            }
            commands.add(new FixedPriceOrderCommand(
                    message.requestId(),
                    message.userId(),
                    message.addressId(),
                    item.getProductId(),
                    item.getSeckillPrice(),
                    1,
                    SECKILL_REMARK));
            deductions.add(
                    new SeckillStockBatchDeductor.Deduction(message.requestId(), item.getId(), message.bucketNo(), 1));
        }
        if (commands.isEmpty()) {
            return new BatchPersistResult(outcomes);
        }

        Map<String, String> existing = tradeService.findExistingSeckillOrders(toPairs(commands));
        List<FixedPriceOrderCommand> toCreate = new ArrayList<>(commands.size());
        Set<String> creating = new HashSet<>();
        for (FixedPriceOrderCommand command : commands) {
            String orderNo = existing.get(command.requestId());
            if (orderNo != null) {
                outcomes.add(BatchOrderOutcome.success(command.requestId(), command.userId(), orderNo));
                continue;
            }
            toCreate.add(command);
            creating.add(command.requestId());
        }

        List<SeckillStockBatchDeductor.Deduction> toDeduct = deductions.stream()
                .filter(d -> creating.contains(d.requestId()))
                .toList();
        if (!toDeduct.isEmpty()) {
            stockDeductor.deduct(toDeduct);
        }

        if (!toCreate.isEmpty()) {
            Map<String, String> created = tradeService.createSeckillOrdersBatch(toCreate);
            for (FixedPriceOrderCommand command : toCreate) {
                String orderNo = created.get(command.requestId());
                if (orderNo == null) {
                    throw new IllegalStateException("批量建单未返回订单号：" + command.requestId());
                }
                outcomes.add(BatchOrderOutcome.success(command.requestId(), command.userId(), orderNo));
            }
        }
        log.info("[秒杀批量] 批量落库完成：消息数={}, 新建={}, 幂等命中={}", unique.size(), toCreate.size(), existing.size());
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

    private Map<Long, SeckillItem> loadItems(Collection<SeckillOrderMessage> messages) {
        List<Long> itemIds = messages.stream()
                .map(SeckillOrderMessage::itemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SeckillItem> items = new LinkedHashMap<>();
        if (itemIds.isEmpty()) {
            return items;
        }
        for (SeckillItem item : seckillItemMapper.selectBatchIds(itemIds)) {
            items.put(item.getId(), item);
        }
        return items;
    }

    /** 批量读回商品，供「已下架 / 不存在」在扣库存之前就按业务失败摘出去。 */
    private Map<Long, Product> loadProducts(Collection<SeckillItem> items) {
        List<Long> productIds = items.stream()
                .map(SeckillItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return productIds.isEmpty() ? Map.of() : productStockService.findByIds(productIds);
    }

    /** 返回失败原因；校验通过返回 {@code null}。 */
    private String validate(SeckillOrderMessage message, SeckillItem item) {
        if (message.userId() == null || message.addressId() == null || message.itemId() == null) {
            return "抢购参数不合法";
        }
        if (item == null || item.getProductId() == null || item.getSeckillPrice() == null) {
            return "秒杀商品不存在";
        }
        if (item.getNotStart() != null && item.getNotStart() == 1) {
            return "本场秒杀尚未开始，请先预约提醒";
        }
        return null;
    }

    private List<OrderRequestPair> toPairs(List<FixedPriceOrderCommand> commands) {
        return commands.stream()
                .map(command -> new OrderRequestPair(command.userId(), command.requestId()))
                .toList();
    }
}
