package com.geekmall.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.cache.CacheNames;
import com.geekmall.common.cache.RedisBloomFilter;
import com.geekmall.common.cache.ResilientRedisCache;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.product.entity.Product;
import com.geekmall.modules.product.mapper.ProductMapper;
import com.geekmall.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 缓存三防集成测试：真实 Redis + 真实 MySQL + 真实 Spring 缓存切面。
 *
 * <p>这些断言是「三防真的生效」与「只是写了文档」之间的分界线：</p>
 * <ul>
 *   <li><b>穿透</b>：不存在的商品 ID 必须被布隆过滤器挡在数据库之外，或只查库一次（空值缓存）；</li>
 *   <li><b>击穿</b>：冷 key 被 8 个线程同时请求时，重建只允许发生一次；</li>
 *   <li><b>雪崩</b>：本地 L1 白名单只覆盖能容忍短暂陈旧的聚合数据。</li>
 * </ul>
 */
@DisplayName("缓存三防集成测试")
class CacheGovernanceIntegrationTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisBloomFilter productBloomFilter;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    /* ------------------------------ 辅助方法 ------------------------------ */

    /** 新建一个在售商品，并纳入布隆过滤器（模拟真实上架流程）。 */
    private Product createOnShelfProduct(String title) {
        Product product = new Product();
        product.setShopId(DEMO_SHOP_ID);
        product.setTitle(title);
        product.setCategoryId(1L);
        product.setCategoryKey("cache-test");
        product.setParentKey("cache-test");
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
        product.setCategoryName("缓存测试分类");
        product.setBrandName("极客严选");
        product.setIsHot(0);
        product.setIsNew(1);
        productMapper.insert(product);
        productBloomFilter.add(String.valueOf(product.getId()));
        return product;
    }

    private String cacheKey(String cacheName, Object key) {
        return "mall:cache:" + cacheName + "::" + key;
    }

    private double rebuildCount() {
        Counter counter = meterRegistry.find("mall_cache_rebuild_total")
                .tag("cache", CacheNames.PRODUCT_DETAIL)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    /* ------------------------------ 穿透 ---------------------------------- */

    @Test
    @DisplayName("防穿透（空值缓存）：不存在的商品只会查库一次，结果以空值形式缓存")
    void nullValueShouldBeCached() {
        long missingId = 900_000_000L + ThreadLocalRandom.current().nextInt(10_000_000);
        // 伪装成「可能存在」，逼请求走到数据库，从而验证空值缓存本身
        productBloomFilter.add(String.valueOf(missingId));
        String key = cacheKey(CacheNames.PRODUCT_DETAIL, missingId);
        stringRedisTemplate.delete(key);

        JsonNode first = get("/api/v1/products/" + missingId, null);
        assertThat(first.get("code").asInt()).isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThat(stringRedisTemplate.hasKey(key))
                .as("不存在商品的查询结果必须写入空值缓存，否则同一无效 ID 会反复打库")
                .isTrue();

        JsonNode second = get("/api/v1/products/" + missingId, null);
        assertThat(second.get("code").asInt()).isEqualTo(ResultCode.NOT_FOUND.getCode());

        Long ttlSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds)
                .as("空值缓存的 TTL 必须小于实体缓存，否则新上架商品会被长期挡在 404")
                .isNotNull()
                .isBetween(1L, 60L);
    }

    @Test
    @DisplayName("防穿透（布隆过滤器）：一定不存在的 ID 直接 404，不写任何缓存（说明没查库）")
    void bloomFilterShouldBlockMissingIdWithoutTouchingDatabase() {
        long missingId = 800_000_000L + ThreadLocalRandom.current().nextInt(10_000_000);
        // 布隆过滤器存在约 1% 的假阳性；若该 ID 恰好被误判，跳过本用例而不是产生随机红灯
        assumeThat(productBloomFilter.mightContain(String.valueOf(missingId)))
                .as("该 ID 被布隆过滤器误判为可能存在，跳过本次断言")
                .isFalse();
        String key = cacheKey(CacheNames.PRODUCT_DETAIL, missingId);
        stringRedisTemplate.delete(key);

        JsonNode response = get("/api/v1/products/" + missingId, null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThat(stringRedisTemplate.hasKey(key))
                .as("布隆过滤器判定为「不存在」时不应产生任何缓存写入，说明请求根本没到达数据库")
                .isFalse();
    }

    @Test
    @DisplayName("防穿透：新建并上架的商品能立刻被访问（布隆过滤器已同步，不会被误判为不存在）")
    void newlyOnShelfProductShouldBeVisible() {
        Product product = createOnShelfProduct("新增商品可见性");

        JsonNode data = assertSuccess(get("/api/v1/products/" + product.getId(), null));

        assertThat(data.get("title").asText()).isEqualTo("新增商品可见性");
    }

    /* ------------------------------ 击穿 ---------------------------------- */

    @Test
    @DisplayName("防击穿：冷 key 被 8 个线程并发请求时，回源重建只发生一次")
    void concurrentColdMissShouldRebuildOnlyOnce() throws Exception {
        Product product = createOnShelfProduct("缓存击穿并发商品");
        String key = cacheKey(CacheNames.PRODUCT_DETAIL, product.getId());
        stringRedisTemplate.delete(key);

        double before = rebuildCount();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREADS);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        JsonNode response = get("/api/v1/products/" + product.getId(), null);
                        if (response.get("code").asInt() != 0) {
                            errors.add(new AssertionError("并发请求失败：" + response));
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                }));
            }
            startGate.countDown();
            assertThat(finishGate.await(30, TimeUnit.SECONDS))
                    .as("并发请求未在超时时间内完成")
                    .isTrue();
            for (Future<?> future : futures) {
                future.get(1, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("并发请求不应产生异常或非成功响应").isEmpty();
        assertThat(rebuildCount() - before)
                .as("8 个线程并发未命中，只允许一个节点回源重建（这正是防击穿的全部意义）")
                .isEqualTo(1.0d);
    }

    /* ------------------------------ 雪崩 ---------------------------------- */

    @Test
    @DisplayName("防雪崩（多级缓存）：本地 L1 只覆盖白名单内的聚合缓存，价格敏感的商品详情不进 L1")
    void localCacheShouldFollowWhitelist() {
        Cache homeFloors = cacheManager.getCache(CacheNames.HOME_FLOORS);
        Cache categoryTree = cacheManager.getCache(CacheNames.CATEGORY_TREE);
        Cache productDetail = cacheManager.getCache(CacheNames.PRODUCT_DETAIL);

        assertThat(homeFloors).isInstanceOf(ResilientRedisCache.class);
        assertThat(((ResilientRedisCache) homeFloors).isLocalCacheEnabled())
                .as("首页楼层是聚合数据，允许秒级陈旧，启用 L1 挡住瞬时冲击")
                .isTrue();
        assertThat(((ResilientRedisCache) categoryTree).isLocalCacheEnabled()).isTrue();
        assertThat(((ResilientRedisCache) productDetail).isLocalCacheEnabled())
                .as("商品详情含价格，商家改价后必须尽快生效，因此不启用 L1")
                .isFalse();
    }

    @Test
    @DisplayName("防雪崩（TTL 抖动）：多次写入同一缓存得到互不相同的过期时间，且都落在抖动区间内")
    void cacheTtlShouldBeJittered() {
        Set<Long> observedTtls = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Product product = createOnShelfProduct("TTL 抖动商品-" + i);
            observedTtls.add(writeAndReadTtl(product.getId()));
        }

        assertThat(observedTtls)
                .as("多次写入若得到完全相同的 TTL，说明抖动没生效，key 会在同一秒集中失效")
                .hasSizeGreaterThan(1);
        assertThat(observedTtls)
                .as("抖动幅度应受 mall.cache.jitter-ratio 约束（productDetail 基准 180s ± 20%）")
                .allSatisfy(ttl -> assertThat(ttl).isBetween(140L, 220L));
    }

    /** 触发一次缓存写入并读取该 key 的剩余 TTL（秒）。 */
    private long writeAndReadTtl(long productId) {
        String key = cacheKey(CacheNames.PRODUCT_DETAIL, productId);
        stringRedisTemplate.delete(key);
        assertSuccess(get("/api/v1/products/" + productId, null));
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).as("缓存未写入，无法验证 TTL 抖动").isNotNull().isGreaterThan(0L);
        return ttl;
    }
}
