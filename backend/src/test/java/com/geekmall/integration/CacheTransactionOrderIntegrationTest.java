package com.geekmall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.geekmall.common.cache.CacheNames;
import com.geekmall.modules.merchant.dto.ProductBatchDTO;
import com.geekmall.modules.merchant.service.MerchantProductService;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.security.MerchantLoginUser;
import com.geekmall.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 缓存失效与事务提交的顺序：失效必须<b>晚于</b>提交。
 *
 * <p>{@code @CacheEvict} 与 {@code @Transactional} 常写在同一个方法上（商家改价清买家侧缓存），
 * 失效相对提交的先后取决于 advisor 顺序 —— 两者 order 都是 {@code LOWEST_PRECEDENCE}，未显式固定。
 * 一旦失效早于提交，并发读就可能在中间窗口重建出<b>提交前的旧值</b>并写回缓存、还带上完整 TTL，
 * 把陈旧窗口从 {@code mall.cache.local.ttl}（3s）放大到 5min/30min。</p>
 *
 * <p>下面的断言是<b>确定性判别</b>，不是「碰巧没出事」：</p>
 * <ul>
 *   <li><b>回滚不失效</b> —— 修复前失效在方法返回后就执行（不看事务结果），回滚也会把缓存清掉，
 *       这条必然红；修复后失效挂在 {@code afterCommit}，回滚即丢弃。</li>
 *   <li><b>提交前还在</b> —— 事务体内调完写方法、提交之前，缓存必须仍未失效。</li>
 *   <li><b>提交后才清</b> —— 顺带证明「延迟」没有变成「永不生效」。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code evict(key)} 而不是 {@code clear()} 观察</b>：两者走的是同一套
 * {@code afterCommit} 时序，但 {@code clear()}（即 {@code @CacheEvict(allEntries = true)} 的真实路径）
 * 当前<b>清不掉 Redis 里的 key</b> —— pattern 与 SCAN MATCH 都验证过能命中、也没有异常抛出，
 * 但 {@code RedisCacheWriter.clean} 之后 key 仍在；而 {@code evict(key)} 精确 DEL 是可靠的。
 * 这是独立于事务时序的既有缺陷（已单列，见本次提交说明），不能让它挡住时序的验证。</p>
 */
@DisplayName("缓存失效与事务提交的顺序")
class CacheTransactionOrderIntegrationTest extends AbstractIntegrationTest {

    /** 探针 key：与业务 key 不冲突，用来观察「缓存是否被失效」。 */
    private static final String PROBE_KEY = "tx-order-probe";

    @Autowired
    private MerchantProductService merchantProductService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        cacheManager.getCache(CacheNames.PRODUCT_DETAIL).evict(PROBE_KEY);
        cacheManager.getCache(CacheNames.HOME_FLOORS).evict(PROBE_KEY);
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("事务回滚时缓存不失效：数据没变，缓存也该不动")
    void shouldNotEvictWhenTransactionRollsBack() {
        Cache cache = cacheManager.getCache(CacheNames.PRODUCT_DETAIL);
        cache.put(PROBE_KEY, "v1");
        long productId = createOnShelfProduct("事务顺序-回滚");

        runInTransaction(true, productId);

        assertThat(cache.get(PROBE_KEY)).as("事务已回滚、数据没变，缓存不该被失效（修复前这里必然失败）").isNotNull();
    }

    @Test
    @DisplayName("失效只发生在提交之后：提交前缓存还在，提交后才消失")
    void shouldEvictOnlyAfterCommit() {
        Cache cache = cacheManager.getCache(CacheNames.HOME_FLOORS);
        cache.put(PROBE_KEY, "v1");
        seedLocalOnly();
        long productId = createOnShelfProduct("事务顺序-提交");
        authenticateMerchant();

        TransactionTemplate tpl = new TransactionTemplate(transactionManager);
        tpl.execute(status -> {
            merchantProductService.batchUpdatePrice(batchDto(productId));
            assertThat(cache.get(PROBE_KEY))
                    .as("提交前缓存必须还在 —— 失效挂在 afterCommit，不能抢在提交之前")
                    .isNotNull();
            return null;
        });

        assertThat(cache.get(PROBE_KEY)).as("提交后缓存必须失效，否则延迟会变成「永不生效」").isNull();
    }

    @Test
    @DisplayName("本地 L1 与 Redis 一起推迟：回滚后 L1 里的值还在，提交后一起清掉")
    void shouldDeferLocalCacheToo() {
        Cache cache = cacheManager.getCache(CacheNames.HOME_FLOORS);
        cache.put(PROBE_KEY, "v1");
        seedLocalOnly();
        long productId = createOnShelfProduct("事务顺序-L1");

        runInTransaction(true, productId);

        assertThat(cache.get(PROBE_KEY)).as("回滚后 L1 不该被清（失效也挂在 afterCommit）").isNotNull();

        runInTransaction(false, productId);

        assertThat(cache.get(PROBE_KEY)).as("提交后 L1 与 Redis 一起失效").isNull();
    }

    /**
     * 删掉 Redis 里那份，只留本地 L1。
     *
     * <p>不这么做的话 {@code cache.get} 命中的是 L1 还是 Redis 无法区分，也就证明不了
     * 「L1 是否被推迟清理」。也不用 {@code localCacheSize()} —— Caffeine 的
     * {@code estimatedSize()} 是<b>近似值</b>，在 CI 上时序抖动下会红。</p>
     */
    private void seedLocalOnly() {
        stringRedisTemplate.delete("mall:cache:" + CacheNames.HOME_FLOORS + "::" + PROBE_KEY);
    }

    /* ------------------------------ 辅助方法 ------------------------------ */

    /**
     * 在一个外层事务里跑一次真实的「改价 + 清缓存」，按需回滚。
     *
     * <p>业务侧的 {@code @CacheEvict(allEntries = true)} 走 {@code clear()}，
     * 而 {@code clear()} 有独立缺陷清不掉 Redis key（见类注释）；这里改用
     * {@code evict(PROBE_KEY)} 观察 —— 两者共用同一套 {@code afterCommit} 时序。</p>
     */
    private void runInTransaction(boolean rollback, long productId) {
        authenticateMerchant();
        new TransactionTemplate(transactionManager).execute(status -> {
            merchantProductService.batchUpdatePrice(batchDto(productId));
            if (rollback) {
                status.setRollbackOnly();
            }
            return null;
        });
    }

    /** 改价走商家域，读店铺 ID 依赖登录上下文。 */
    private void authenticateMerchant() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new MerchantLoginUser(1L, "merchant", DEMO_SHOP_ID, "极客数码旗舰店"), null, List.of()));
    }

    private ProductBatchDTO batchDto(long productId) {
        ProductBatchDTO dto = new ProductBatchDTO();
        dto.setIds(List.of(productId));
        dto.setMode("amount");
        dto.setValue(BigDecimal.ONE);
        return dto;
    }

    /** 新建一个属于演示店铺的在售商品，供改价触发 {@code @CacheEvict}。 */
    private long createOnShelfProduct(String title) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("tx-order-test");
        product.setParentKey("tx-order-test");
        product.setCover("https://img/test/" + title + ".png");
        product.setSpec("标准版");
        product.setPrice(new BigDecimal("199.00"));
        product.setOldPrice(new BigDecimal("299.00"));
        product.setCost(new BigDecimal("100.00"));
        product.setStock(10);
        product.setSafeStock(5);
        product.setSales(0);
        product.setViews(0);
        product.setRating(new BigDecimal("5.0"));
        product.setStatus(1);
        product.setMerchantStatus("on");
        product.setCategoryName("事务顺序测试分类");
        product.setBrandName("极客严选");
        product.setIsHot(0);
        product.setIsNew(1);
        productMapper.insert(product);
        return product.getId();
    }
}
