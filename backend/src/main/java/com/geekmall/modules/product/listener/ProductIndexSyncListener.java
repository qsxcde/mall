package com.geekmall.modules.product.listener;

import com.geekmall.common.event.ProductChangedEvent;
import com.geekmall.modules.product.search.ProductSearchIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 商品变更 → 搜索索引增量同步。
 *
 * <p>三个注解各自负责一件事，缺一不可：</p>
 * <ul>
 *   <li>{@code AFTER_COMMIT}：只有商品事务真正提交后才写索引。否则回滚掉的新增商品
 *       会留在索引里，变成「搜得到、点进去 404」的幽灵商品；</li>
 *   <li>{@code @Async("messageExecutor")}：索引写入是外部网络调用，同步执行会把 ES 的
 *       延迟（最坏 5s socket 超时）串到商家的保存/上下架请求上。有界线程池 + 拒满降级
 *       （{@code CallerRunsPolicy}）保证高峰期也不会无限堆积；</li>
 *   <li>{@code fallbackExecution = true}：无事务时也执行。商品维护目前都在事务里，
 *       但把「没有事务就静默丢事件」这种隐式前提留给后来者迟早踩坑。</li>
 * </ul>
 *
 * <p>仅在 {@code mall.search.engine=elasticsearch} 时装配：其它引擎的索引就是 MySQL 本身，
 * 不存在同步这件事，装一个空监听器只会误导阅读者。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.search.engine", havingValue = "elasticsearch")
public class ProductIndexSyncListener {

    private final ProductSearchIndexer productSearchIndexer;

    @Async("messageExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductChangedEvent event) {
        try {
            productSearchIndexer.sync(event.productId());
        } catch (Exception e) {
            // 索引器内部已降级；这里再兜一层，防止未预料的异常把线程池任务变成 ERROR 噪音
            log.warn("[搜索索引] 商品 {} 增量同步异常（{}）：{}", event.productId(), event.reason(), e.getMessage());
        }
    }
}
