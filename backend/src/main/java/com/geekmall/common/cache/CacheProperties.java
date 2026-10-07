package com.geekmall.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存治理配置（{@code mall.cache.*}）。
 *
 * <p>把「基础 TTL / 空值 TTL / 抖动比例 / 击穿锁参数 / 本地 L1 白名单」全部外置，
 * 使缓存行为可在不改代码的前提下按环境调参（例如压测时关闭 L1 以观察真实 Redis 压力）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.cache")
public class CacheProperties {

    /** 各缓存的基础 TTL；未声明的缓存用 {@link #defaultTtl}。 */
    private Map<String, Duration> ttl = new LinkedHashMap<>(Map.of(
            CacheNames.HOME_FLOORS, Duration.ofMinutes(5),
            CacheNames.CATEGORY_TREE, Duration.ofMinutes(30),
            CacheNames.PRODUCT_DETAIL, Duration.ofMinutes(3),
            CacheNames.PRODUCT_RECOMMEND, Duration.ofMinutes(5)
    ));

    /** 未显式配置 TTL 的缓存使用的兜底 TTL。 */
    private Duration defaultTtl = Duration.ofMinutes(5);

    /**
     * TTL 抖动比例（0~1）。
     *
     * <p>防雪崩的关键：同一批 key 若 TTL 完全相同，会在同一秒集中失效，
     * 瞬时全部回源数据库。按比例随机浮动后失效时刻被打散。</p>
     */
    private double jitterRatio = 0.2;

    /**
     * 空值（不存在的实体）缓存时长。
     *
     * <p>防穿透的第二道：布隆过滤器只能挡住「一定不存在」的请求，
     * 被误判为「可能存在」的无效 id 会落到数据库；把这次查询结果（空值）
     * 也缓存一小段时间，可避免同一无效 id 被反复打库。</p>
     *
     * <p>取短值是为了不让「刚上架的商品」被空值缓存长期挡住。</p>
     */
    private Duration nullTtl = Duration.ofSeconds(60);

    /** 击穿防护：热点 key 失效瞬间只放一个线程回源重建。 */
    private Lock lock = new Lock();

    /**
     * 本地一级缓存（Caffeine）。
     *
     * <p>Redis 故障时的熔断降级参数已迁到 {@code mall.resilience}（见
     * {@link com.geekmall.common.resilience.ResilienceProperties}），
     * 由缓存层与业务级共用同一个熔断状态机，避免同一依赖出现两套计数。</p>
     */
    private Local local = new Local();

    @Data
    public static class Lock {

        /** 击穿防护开关。关闭后退化为 Spring 默认的「进程内锁」行为。 */
        private boolean enabled = true;

        /** 互斥锁自身 TTL，防止持锁线程崩溃导致锁泄漏（死锁）。 */
        private Duration ttl = Duration.ofSeconds(3);

        /**
         * 未抢到锁的线程最长等待时间。
         *
         * <p>超时后不再等待，直接放行回源 —— 宁可多查几次库，
         * 也不能让请求被锁拖到超时（可用性优先）。</p>
         */
        private Duration waitTimeout = Duration.ofMillis(800);

        /** 等待期间的轮询间隔。 */
        private Duration retryInterval = Duration.ofMillis(50);
    }

    @Data
    public static class Local {

        /** 本地 L1 总开关。 */
        private boolean enabled = true;

        /**
         * 启用本地 L1 的缓存名白名单。
         *
         * <p>只有「能容忍数秒陈旧」的聚合类数据才进 L1。商品详情涉及价格，
         * 商家改价后希望尽快生效，因此默认<b>不</b>给 L1。</p>
         */
        private List<String> names = List.of(CacheNames.HOME_FLOORS, CacheNames.CATEGORY_TREE);

        /** L1 存活时长。必须远小于 Redis TTL，否则失去「多级」的意义。 */
        private Duration ttl = Duration.ofSeconds(3);

        /** L1 最大条目数，防止多实例低内存环境被撑爆。 */
        private long maxSize = 10_000;
    }
}
