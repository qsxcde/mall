package com.geekmall.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式限流器单元测试。重点是<b>降级语义</b> —— 限流组件自己故障时的行为，
 * 这是最容易在实现里被忽略、线上却最难排查的一环。
 */
@ExtendWith(MockitoExtension.class)
class DistributedRateLimiterTest {

    private static final String KEY = "mall:rate:auth-login:ip:127.0.0.1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private DistributedRateLimiter limiter;

    @SuppressWarnings("unchecked")
    private void stubCount(Long count) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(count);
    }

    @Nested
    @DisplayName("正常计数")
    class Normal {

        @Test
        @DisplayName("计数未超阈值时放行，并回传剩余额度")
        void allowsBelowLimit() {
            stubCount(3L);

            RateLimitResult result = limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN);

            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isEqualTo(7);
            assertThat(result.limit()).isEqualTo(10);
        }

        @Test
        @DisplayName("刚好等于阈值仍放行（阈值是「允许的最大次数」）")
        void allowsExactlyAtLimit() {
            stubCount(10L);

            assertThat(limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN).allowed()).isTrue();
        }

        @Test
        @DisplayName("超阈值时拒绝，重试等待取 Redis 中该 key 的剩余 TTL")
        void rejectsAboveLimitUsingTtl() {
            stubCount(11L);
            when(redisTemplate.getExpire(KEY)).thenReturn(42L);

            RateLimitResult result = limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(42);
            assertThat(result.remaining()).isZero();
        }

        @Test
        @DisplayName("TTL 缺失时回退为窗口长度，保证 Retry-After 始终有意义")
        void fallsBackToWindowWhenTtlMissing() {
            stubCount(11L);
            when(redisTemplate.getExpire(KEY)).thenReturn(-1L);

            assertThat(limiter.tryAcquire(KEY, 10, 30, RateLimitFallback.FAIL_OPEN).retryAfterSeconds())
                    .isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("故障降级")
    class Degradation {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("Redis 异常 + FAIL_OPEN：放行，避免限流组件拖垮正常业务")
        void failOpenAllowsWhenRedisDown() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new RedisConnectionFailureException("connection refused"));

            assertThat(limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN).allowed()).isTrue();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("Redis 异常 + FAIL_CLOSED：拒绝，保证防撞库/防短信轰炸能力不失效")
        void failClosedRejectsWhenRedisDown() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new RedisConnectionFailureException("connection refused"));

            RateLimitResult result = limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_CLOSED);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isPositive();
        }

        @Test
        @DisplayName("Redis 返回 null（脚本执行未返回结果）同样按降级策略处理")
        void nullResultDegrades() {
            stubCount(null);

            assertThat(limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN).allowed()).isTrue();
            assertThat(limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_CLOSED).allowed()).isFalse();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("任意非受检异常都不会向外抛：限流器绝不能把业务请求打成 500")
        void neverPropagatesException() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new IllegalStateException("unexpected"));

            assertThat(limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("原子性契约")
    class Atomicity {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("自增与设置过期在同一段 Lua 内完成，避免遗留永不过期的计数 key")
        void usesAtomicLuaScript() {
            stubCount(1L);

            limiter.tryAcquire(KEY, 10, 60, RateLimitFallback.FAIL_OPEN);

            ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
            verify(redisTemplate).execute(scriptCaptor.capture(), anyList(), eq("61"));

            String script = scriptCaptor.getValue().getScriptAsString();
            assertThat(script).contains("INCR", "EXPIRE", "count == 1");
            assertThat(scriptCaptor.getValue().getResultType()).isEqualTo(Long.class);
        }
    }
}
