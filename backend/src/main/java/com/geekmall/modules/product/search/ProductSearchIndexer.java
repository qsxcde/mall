package com.geekmall.modules.product.search;

/**
 * 商品索引的增量写入能力。
 *
 * <p><b>为什么需要这一层</b>：项目原先只有 {@code loadtest/scripts/es_sync_products.py}
 * 这个<b>离线全量</b>导入脚本。这意味着商家在后台改标题、调价、上下架之后，
 * 索引要等到下一次人工重跑脚本才会更新 —— 在这段时间里买家搜到的是旧价、
 * 或者搜到已经下架的商品。全量脚本解决不了「刚改完就该生效」这个问题，
 * 必须有一条随写随同步的增量路径。</p>
 *
 * <p>实现只有一个（{@link ElasticProductSearchIndexer}，且仅在
 * {@code mall.search.engine=elasticsearch} 时注册）：其它引擎的索引就是 MySQL 本身，
 * 不存在「同步」这件事，因此不需要 no-op 实现，监听器也一并条件装配。</p>
 */
public interface ProductSearchIndexer {

    /**
     * 按商品 ID 同步一条索引。
     *
     * <p>语义是「以数据库当前状态为准」：可索引（上架且未删）则覆盖写入，
     * 否则从索引中<b>删除</b>。做成「按 id 重新读库」而不是「把事件里的快照写进去」，
     * 是为了让重复投递、乱序投递都收敛到同一结果 —— 索引是派生数据，
     * 只要最终一致就够，不需要事件本身携带完整字段。</p>
     *
     * <p>本方法<b>不抛业务异常</b>：索引失败只应影响搜索新鲜度，
     * 绝不能反过来让商家的保存操作失败。</p>
     */
    void sync(Long productId);

    /** 引擎标识，用于日志定位。 */
    String engineName();
}
