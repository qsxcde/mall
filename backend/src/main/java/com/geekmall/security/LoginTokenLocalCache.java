package com.geekmall.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

/**
 * 登录令牌的本地短缓存（带跨实例失效能力）。
 *
 * <p>鉴权路径每个请求都要比对 Redis 会话；加 30s 本地缓存后，命中即完全不出网，
 * 把鉴权路径的 Redis QPS 降一个数量级。</p>
 *
 * <h3>键为什么是「令牌」而不是 userId</h3>
 * <p>按 userId 缓存时有一个致命缺陷：同一用户在 30s 内重新登录，Redis 已写入新令牌，
 * 但本地缓存仍持有旧令牌且命中后不会回源 Redis，于是刚签发的新令牌被判为失效 ——
 * 表现为「登录成功却满屏 401」，前端 401 处理会清令牌跳登录页，形成最长 30s 的登录死循环。
 * 以令牌为键后，新令牌天然是缓存未命中，会回源校验，行为正确。</p>
 *
 * <h3>那怎么按 userId 失效</h3>
 * <p>登出 / 顶下线 / 改密只知道 userId，因此这里额外维护一份
 * {@code userId → 该用户最近校验通过的令牌} 的反向索引，并在
 * {@link #get} 命中时同步更新；Redis 会话本身就是「单用户单令牌」语义，
 * 所以「让每个用户在本实例最多只保留一条缓存」是安全的 ——
 * 新令牌被校验通过时，旧令牌的缓存会被立即剔除。</p>
 *
 * <p>配合 {@code mall:auth:session:invalidated} 广播，跨实例失效延迟从 30s 降到毫秒级；
 * 广播不可用时仍有 30s TTL 兜底。</p>
 */
@Slf4j
@Component
public class LoginTokenLocalCache {

    /** 本地缓存存活时长：也是广播失效不可用时的最大生效延迟。 */
    private static final Duration TTL = Duration.ofSeconds(30);

    /** 容量上限：防止海量令牌（或伪造令牌）撑爆堆内存。 */
    private static final long MAX_SIZE = 100_000L;

    private final Cache<String, LoginUser> byToken;
    private final Cache<Long, String> tokenByUser;

    public LoginTokenLocalCache() {
        this.byToken = Caffeine.newBuilder().maximumSize(MAX_SIZE).expireAfterWrite(TTL).build();
        this.tokenByUser = Caffeine.newBuilder().maximumSize(MAX_SIZE).expireAfterWrite(TTL).build();
    }

    /**
     * 读取缓存，未命中时用 {@code loader} 回源（通常回源 Redis 校验会话）。
     *
     * <p>loader 返回 {@code null} 时 Caffeine 不会缓存空值，下次仍会回源 ——
     * 这正是「令牌已失效」需要的语义。</p>
     */
    public LoginUser get(String token, Function<String, LoginUser> loader) {
        LoginUser user = byToken.get(token, loader);
        if (user != null) {
            // 维持 userId → token 反向索引；同一用户只保留最新令牌，
            // 与 Redis 的「单用户单会话」语义一致，也让 invalidate(userId) 足够彻底
            String previous = tokenByUser.getIfPresent(user.getUserId());
            if (previous != null && !previous.equals(token)) {
                byToken.invalidate(previous);
            }
            tokenByUser.put(user.getUserId(), token);
        }
        return user;
    }

    /**
     * 失效某用户的令牌缓存。
     *
     * <p>由 Pub/Sub 广播触发，也可在同实例内直接调用。幂等，可重复调用。</p>
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        String token = tokenByUser.getIfPresent(userId);
        if (token != null) {
            byToken.invalidate(token);
        }
        tokenByUser.invalidate(userId);
        log.debug("已失效本地登录令牌缓存：userId={}", userId);
    }

    /** 当前缓存的令牌条目数（诊断与测试用）。 */
    public long size() {
        return byToken.estimatedSize();
    }
}
