package com.geekmall.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录令牌本地缓存单元测试（P1-2 缓存收益 / P1-6 跨实例失效）。
 *
 * <p>核心是两条语义：</p>
 * <ol>
 *   <li><b>命中不回源</b>：同一令牌短时间内多次校验只访问一次 Redis；</li>
 *   <li><b>按 userId 失效是彻底的</b>：登出/顶下线广播后，该用户在该实例上的令牌缓存
 *       必须立刻不可命中 —— 否则「登出后旧令牌还能用」，这是安全问题而不只是体验问题。</li>
 * </ol>
 */
class LoginTokenLocalCacheTest {

    private LoginTokenLocalCache cache;

    @BeforeEach
    void setUp() {
        cache = new LoginTokenLocalCache();
    }

    private static LoginUser user(long userId) {
        return new LoginUser(userId, "u" + userId, null);
    }

    @Test
    @DisplayName("命中缓存后不再回源 Redis")
    void shouldHitCacheWithoutReloading() {
        AtomicInteger loads = new AtomicInteger();

        LoginUser first = cache.get("token-A", t -> {
            loads.incrementAndGet();
            return user(1L);
        });
        LoginUser second = cache.get("token-A", t -> {
            loads.incrementAndGet();
            return user(1L);
        });

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(loads.get()).as("第二次应命中缓存，不再回源").isEqualTo(1);
    }

    @Test
    @DisplayName("回源返回 null（令牌已失效）时不缓存空值，下次仍回源")
    void shouldNotCacheNullResult() {
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("token-X", t -> {
            loads.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.get("token-X", t -> {
            loads.incrementAndGet();
            return null;
        })).isNull();

        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("按 userId 失效后必须回源（登出/顶下线立即生效）")
    void invalidateByUserIdShouldForceReload() {
        cache.get("token-A", t -> user(1L));
        cache.get("token-B", t -> user(2L));

        assertThat(cache.size()).isEqualTo(2);

        cache.invalidate(1L);

        AtomicInteger loads = new AtomicInteger();
        cache.get("token-A", t -> {
            loads.incrementAndGet();
            return null; // 模拟 Redis 中该令牌已被删除
        });

        assertThat(loads.get()).as("失效后必须重新校验，而不是继续命中旧缓存").isEqualTo(1);
        // 其他用户的缓存不受影响
        assertThat(cache.get("token-B", t -> user(2L))).isNotNull();
    }

    @Test
    @DisplayName("同一用户换新令牌时，旧令牌缓存被立即剔除（单用户单会话语义）")
    void newTokenForSameUserShouldEvictPrevious() {
        cache.get("old-token", t -> user(1L));
        // 同一用户在本实例上用新令牌校验通过 → 旧令牌缓存应被清掉
        cache.get("new-token", t -> user(1L));

        AtomicInteger loads = new AtomicInteger();
        cache.get("old-token", t -> {
            loads.incrementAndGet();
            return null; // Redis 中已是新令牌，旧令牌校验失败
        });

        assertThat(loads.get()).as("旧令牌应已从缓存剔除，需回源校验").isEqualTo(1);
        assertThat(cache.get("new-token", t -> user(1L))).isNotNull();
    }

    @Test
    @DisplayName("失效 null userId 安全无副作用")
    void invalidateNullIsNoop() {
        cache.get("token-A", t -> user(1L));

        cache.invalidate(null);

        assertThat(cache.get("token-A", t -> user(1L))).isNotNull();
    }
}
