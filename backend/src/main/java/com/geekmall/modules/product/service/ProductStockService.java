package com.geekmall.modules.product.service;

import com.geekmall.modules.product.entity.Product;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品库存与批量读取能力（跨域共用）。
 *
 * <h3>为什么需要这个服务</h3>
 * <p>原本 trade / inventory / marketing / cart / review 五个域各自直接
 * {@code import ProductMapper} 并调用 {@code deductStock} / {@code restoreStock} / {@code selectById}。
 * 问题不在于「多写了几行」，而在于：</p>
 * <ol>
 *   <li><b>扣减规则没有单一所有者</b>：{@code deductStock} 的语义是
 *       「{@code WHERE stock >= qty} 条件更新，影响行数为 0 即库存不足」。
 *       这条规则被 5 个域各自理解、各自处理返回值，任何一处误判都会导致<b>超卖</b>；</li>
 *   <li><b>无法统一加防护</b>：将来若要给扣减加指标、加日志、加熔断，必须改 5 个域；</li>
 *   <li><b>分层倒置</b>：上层业务域直接依赖下层的数据访问层。</li>
 * </ol>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li><b>扣减返回布尔</b>：把「影响行数 == 0 即库存不足」的判断收进商品域，
 *       调用方不需要知道 SQL 细节，也避免把 {@code int rows} 误当成功数；</li>
 *   <li><b>不在此处开事务</b>：本服务只做单条语句的原子操作，
 *       事务边界由调用方（下单 / 取消）决定，否则会造成事务嵌套；</li>
 *   <li><b>批量读取显式去重</b>：避免调用方传入重复 id 造成的冗余查询。</li>
 * </ul>
 */
public interface ProductStockService {

    /**
     * 扣减库存（条件更新，天然防超卖）。
     *
     * @return {@code true} 扣减成功；{@code false} 库存不足（未做任何修改）
     */
    boolean deductStock(Long productId, int qty);

    /**
     * 回滚库存（订单取消 / 超时未支付 / 秒杀预占释放）。
     *
     * <p>回滚本身<b>不是幂等的</b> —— 必须由调用方用唯一索引或幂等日志保证只回滚一次
     * （本项目用 {@code inventory_rollback_log} 的 {@code uk_order_product} 兜底）。
     * 本方法只负责那条 UPDATE，不承担幂等判断。</p>
     */
    void restoreStock(Long productId, int qty);

    /** 按 ID 查询商品（含下架/软删的，调用方自行判断状态）。 */
    Product findById(Long productId);

    /** 按 ID 批量查询，返回 {@code id -> Product}；缺失的 id 不会出现在结果中。 */
    Map<Long, Product> findByIds(Collection<Long> productIds);

    /** 按 ID 批量查询并保持入参顺序（搜索结果回表用）。 */
    List<Product> findByIdsInOrder(List<Long> orderedIds);

    /** 按 ID 批量查询「在售」商品（买家可见范围），用于搜索命中后回表。 */
    List<Product> findOnShelfByIds(List<Long> ids);

    // ------------------------------------------------------------------
    // 商家端商品维护（低频写路径）
    // ------------------------------------------------------------------

    /** 按店铺 + 条件分页（商家商品列表）。 */
    com.baomidou.mybatisplus.core.metadata.IPage<Product> pageByShop(
            Long shopId,
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> wrapper,
            long page,
            long size);

    /** 按店铺 + 条件查询列表。 */
    List<Product> listByShop(
            Long shopId, com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> wrapper);

    /**
     * 按店铺 + 商品 ID 查询，不存在或不属于该店时返回 {@code null}。
     */
    Product findByShopAndId(Long shopId, Long productId);

    /** 新增商品（调用方负责填好店铺归属）。 */
    Long insertProduct(Product product);

    /**
     * 按乐观锁更新。
     *
     * @return {@code true} 成功；{@code false} 版本冲突（已被他人修改）
     */
    boolean updateWithVersion(Product product);

    /** 更新（不带版本条件，供状态/价格等批量维护场景）。 */
    int updateProduct(Product product);
}
