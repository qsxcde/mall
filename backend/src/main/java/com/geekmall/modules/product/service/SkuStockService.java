package com.geekmall.modules.product.service;

import com.geekmall.modules.product.entity.Sku;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SKU 库存与批量读取能力（跨域共用）。
 *
 * <p>与 {@link ProductStockService} 同域同纪律，只是把库存粒度从商品下沉到规格 SKU：</p>
 * <ul>
 *   <li><b>扣减返回布尔</b>：「影响行数 == 0 即库存不足」的判读收在这里，调用方不感知 SQL；</li>
 *   <li><b>不在此处开事务</b>：只做单条语句的原子操作，事务边界由调用方（下单 / 取消）决定；</li>
 *   <li><b>两层同句维护</b>：扣减 / 回补在同一条 SQL 里联动 {@code pms_sku.stock} 与
 *       {@code pms_product.stock}（含商品销量），即使调用方无事务也不会撕裂
 *       「{@code SUM(pms_sku.stock) == pms_product.stock}」的商品层守恒。</li>
 * </ul>
 */
public interface SkuStockService {

    /**
     * 扣减 SKU 库存（条件更新，天然防超卖），并同步扣减商品总库存、累加商品销量。
     *
     * @return {@code true} 扣减成功；{@code false} 库存不足（未做任何修改）
     */
    boolean deductSkuStock(Long skuId, int qty);

    /**
     * 回补 SKU 库存（订单取消 / 超时未支付），并同步回补商品总库存、回落商品销量。
     *
     * <p>回补本身<b>不是幂等的</b> —— 由调用方用幂等日志（{@code inventory_rollback_log}）
     * 或桶审计流水保证只回补一次。</p>
     */
    void restoreSkuStock(Long skuId, int qty);

    /** 按 ID 批量查询 SKU，返回 {@code skuId -> Sku}；缺失的 id 不会出现在结果中。 */
    Map<Long, Sku> findByIds(Collection<Long> skuIds);

    /** 按商品查询 SKU（升序 id），无 SKU 返回空列表。 */
    List<Sku> findByProductId(Long productId);

    /**
     * 商品的默认 SKU（首个）ID；商品没有 SKU 时返回 {@code null}。
     *
     * <p>供「只知商品、不知规格」的历史链路（秒杀单、手工出库）解析规格归属：
     * 秒杀商品已被约束为单规格，解析无歧义。</p>
     */
    Long resolveSkuId(Long productId);
}
