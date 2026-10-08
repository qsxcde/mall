package com.geekmall.modules.marketing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀库存桶 mkt_seckill_bucket。
 *
 * <p><b>为什么需要它</b>：{@code mkt_seckill_item.stock} 是一行库存，所有订单都去抢同一把行锁，
 * 落库并行度恒为 1。把一份活动库存预拆成 N 行后，每单只扣其中一行，
 * 命中同一行的概率从 100% 降到 1/N（分桶不消除锁，只分摊锁）。</p>
 *
 * <p><b>与 {@link SeckillItem#getStock()} 的关系</b>：分桶启用后，本表的
 * {@code SUM(stock)} 才是权威余量，{@code SeckillItem.stock} 只是分桶那一刻的初始值快照。</p>
 *
 * <p>主键是 {@code (item_id, bucket_no)} 复合键，因此没有自增 id 字段；
 * 所有读写都走 {@code SeckillBucketMapper} 上的显式 SQL，不使用按 id 的 CRUD。</p>
 */
@Data
@TableName("mkt_seckill_bucket")
public class SeckillBucket implements Serializable {

    /** 所属秒杀活动商品 ID。 */
    private Long itemId;

    /** 桶号，0 起。 */
    private Integer bucketNo;

    /** 桶内余量（CAS 扣减对象）。 */
    private Integer stock;

    /** 桶内初始量，用于守恒对账。 */
    private Integer total;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
