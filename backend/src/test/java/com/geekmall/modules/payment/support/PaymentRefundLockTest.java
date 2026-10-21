package com.geekmall.modules.payment.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class PaymentRefundLockTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private PaymentRefundLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lock = new PaymentRefundLock(redisTemplate);
    }

    @Test
    @DisplayName("抢到锁返回令牌，解锁只删除自己的令牌")
    void acquireAndRelease() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        String token = lock.tryLock("T1", Duration.ofSeconds(30), Duration.ZERO);

        assertThat(token).isNotNull();
        assertThat(token).isNotEqualTo(PaymentRefundLock.DEGRADED_TOKEN);
        lock.unlock("T1", token);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq(token));
    }

    @Test
    @DisplayName("等待超时返回 null，调用方应快速失败")
    void timeoutReturnsNull() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(lock.tryLock("T2", Duration.ofSeconds(30), Duration.ZERO)).isNull();
    }

    @Test
    @DisplayName("Redis 异常时 fail-open，返回降级令牌且解锁不访问 Redis")
    void redisFailureFailsOpen() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        String token = lock.tryLock("T3", Duration.ofSeconds(30), Duration.ZERO);

        assertThat(token).isEqualTo(PaymentRefundLock.DEGRADED_TOKEN);
        lock.unlock("T3", token);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }
}
