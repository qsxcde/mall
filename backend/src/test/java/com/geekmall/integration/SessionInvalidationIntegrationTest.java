package com.geekmall.integration;

import com.geekmall.security.LoginTokenLocalCache;
import com.geekmall.security.LoginUser;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录会话「跨实例立即失效」的端到端验证（P1-6）。
 *
 * <p>鉴权路径有 30s 本地令牌缓存，若不广播，登出/顶下线/改密在<b>其他实例</b>上
 * 最长 30s 才生效 —— 这是安全问题，不只是体验问题。</p>
 *
 * <p>这里验证的不是「缓存类的方法能跑」，而是<b>真实链路</b>：
 * 真实过滤器链写入缓存 → 真实登出接口 → Redis Pub/Sub 广播 → 监听器清缓存。
 * 断言窗口只有 5s，远小于 30s TTL，因此只有广播生效才能通过。</p>
 */
@DisplayName("登录会话跨实例失效（P1-6）")
class SessionInvalidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LoginTokenLocalCache localCache;

    @Test
    @DisplayName("登出后本地令牌缓存被广播立即清除，且旧令牌立刻不再被接受")
    void logoutShouldInvalidateLocalTokenCacheImmediately() {
        String token = registerAndLogin(randomPhone());

        // ① 带令牌请求一次，让真实过滤器链把该令牌写进本地缓存
        assertSuccess(get("/api/v1/user/profile", token));
        assertThat(probe(token)).as("请求后令牌应已进入本地缓存").isNotNull();

        // ② 登出：删除 Redis 会话并广播失效
        assertSuccess(post("/api/v1/auth/logout", null, token));

        // ③ 广播是异步的：轮询等待缓存被清掉（窗口 5s << 30s TTL，只有广播能解释）
        assertThat(waitUntilEvicted(token, Duration.ofSeconds(5)))
                .as("登出广播应让本实例立即清掉该令牌的缓存")
                .isTrue();

        // ④ 行为层面：旧令牌立即失效
        assertThat(get("/api/v1/user/profile", token).get("code").asInt())
                .as("登出后旧令牌必须立刻不可用")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("同一用户重新登录：旧令牌被顶下线，新令牌可用")
    void reloginShouldKickOutPreviousToken() {
        String phone = randomPhone();
        String first = registerAndLogin(phone);

        // 旧令牌进入本实例的本地缓存
        assertSuccess(get("/api/v1/user/profile", first));
        assertThat(probe(first)).isNotNull();

        // JWT 的 iat/exp 精度到秒、且没有 jti，因此同一秒内两次登录会签发完全相同的令牌。
        // 这里显式跨过秒边界，确保「旧令牌」与「新令牌」确实是两个不同的令牌，
        // 否则「顶下线」这个场景不成立（两个 token 相同，本就无法区分）。
        crossSecondBoundary();

        // 重新登录（单点登录语义：Redis 会话被覆盖，并广播失效）
        String second = loginBuyer(phone, DEMO_PASSWORD);
        assertThat(second).as("两次登录应签发不同令牌").isNotEqualTo(first);

        assertThat(waitUntilEvicted(first, Duration.ofSeconds(5)))
                .as("重新登录广播后，旧令牌缓存应立即失效")
                .isTrue();
        assertThat(get("/api/v1/user/profile", first).get("code").asInt())
                .as("旧令牌应被顶下线")
                .isEqualTo(401);
        assertSuccess(get("/api/v1/user/profile", second));
    }

    /**
     * 用「空 loader」探测本地缓存：命中返回缓存的用户；未命中时 loader 返回 null，
     * Caffeine 不缓存空值，于是返回 null —— 正好等价于「该令牌不在缓存里」。
     */
    private LoginUser probe(String token) {
        return localCache.get(token, t -> null);
    }

    /** 等待跨过 JWT 的秒级时间戳边界（令牌 iat/exp 精度为秒）。 */
    private void crossSecondBoundary() {
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitUntilEvicted(String token, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (probe(token) == null) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
