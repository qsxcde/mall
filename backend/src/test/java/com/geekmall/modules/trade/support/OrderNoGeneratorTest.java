package com.geekmall.modules.trade.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 订单号生成器单元测试。
 *
 * <p>核心要证明两件事：</p>
 * <ol>
 *   <li><b>唯一性</b>：一个实例内大量并发不重号；<b>两个实例共享同一 Redis 时，号段互不重叠</b>
 *       —— 这正是原「进程内自增 + 随机位」方案在多实例下会撞号的地方；</li>
 *   <li><b>号段复用</b>：命中本地缓存期间不出网，只有耗尽才 {@code INCRBY} 一次。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OrderNoGeneratorTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** 用一个共享计数器模拟 Redis INCRBY 的全局原子自增。 */
    private AtomicLong stubRedisCounter() {
        AtomicLong counter = new AtomicLong();
        lenient().when(valueOps.increment(anyString(), anyLong()))
                .thenAnswer(invocation -> counter.addAndGet(invocation.getArgument(1)));
        return counter;
    }

    @Test
    @DisplayName("单实例：格式正确且大量发号不重号")
    void shouldGenerateUniqueOrderNo() {
        stubRedisCounter();
        OrderNoGenerator generator = new OrderNoGenerator(redisTemplate);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String no = generator.next();
            assertThat(no).startsWith("GM").hasSize(26);
            assertThat(seen.add(no)).as("订单号重复：%s", no).isTrue();
        }
    }

    @Test
    @DisplayName("号段复用：2500 次发号只需 3 次 Redis INCRBY")
    void shouldReuseSegmentLocally() {
        stubRedisCounter();
        OrderNoGenerator generator = new OrderNoGenerator(redisTemplate);

        for (int i = 0; i < 2500; i++) {
            generator.next();
        }

        // 1000 + 1000 + 1000 恰好覆盖 2500 个号
        verify(valueOps, times(3)).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("多实例：两个实例共享同一 Redis，各自发号互不重叠")
    void shouldNotOverlapAcrossInstances() {
        // 同一份「Redis」被两个实例共享
        stubRedisCounter();
        OrderNoGenerator instanceA = new OrderNoGenerator(redisTemplate);
        OrderNoGenerator instanceB = new OrderNoGenerator(redisTemplate);

        Set<String> fromA = new HashSet<>();
        Set<String> fromB = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            fromA.add(instanceA.next());
            fromB.add(instanceB.next());
        }

        assertThat(fromA).hasSize(2000);
        assertThat(fromB).hasSize(2000);
        // 关键断言：合并后依然全部唯一（号段互不重叠）
        Set<String> merged = new HashSet<>(fromA);
        merged.addAll(fromB);
        assertThat(merged).hasSize(4000);
    }

    @Test
    @DisplayName("多线程并发发号不重号")
    void shouldBeThreadSafe() throws InterruptedException {
        stubRedisCounter();
        OrderNoGenerator generator = new OrderNoGenerator(redisTemplate);

        int threads = 8;
        int perThread = 1000;
        Set<String> all = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicLong duplicates = new AtomicLong();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    if (!all.add(generator.next())) {
                        duplicates.incrementAndGet();
                    }
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(duplicates.get()).isZero();
        assertThat(all).hasSize(threads * perThread);
        // 8000 个号需要 8 个号段（STEP=1000）
        verify(valueOps, times(8)).increment(anyString(), anyLong());
    }
}
