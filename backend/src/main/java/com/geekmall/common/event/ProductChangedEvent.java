package com.geekmall.common.event;

/**
 * 商品发生变更（新增 / 编辑 / 上下架 / 改价）的事件。
 *
 * <p>只携带商品 ID，<b>不携带字段快照</b>。搜索索引是派生数据，消费方按 ID 重新读库，
 * 才能保证「重复投递 / 乱序投递都会收敛到数据库当前状态」；把快照塞进事件里，
 * 一条迟到的旧事件就能把索引回退到旧价格。</p>
 *
 * <p>用 {@code @TransactionalEventListener(AFTER_COMMIT)} 监听可以保证：
 * 只有商品事务真正提交后才写索引，不会出现「索引里有、数据库里没有」的幽灵商品。</p>
 *
 * @param productId 商品 ID
 * @param reason    变更来源（仅用于日志定位，不参与业务判断）
 */
public record ProductChangedEvent(Long productId, String reason) {

    public static ProductChangedEvent of(Long productId, String reason) {
        return new ProductChangedEvent(productId, reason);
    }
}
