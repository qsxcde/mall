package com.geekmall.modules.marketing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.resilience.ResilienceGuard;
import com.geekmall.modules.marketing.bucket.SeckillBucketManager;
import com.geekmall.modules.marketing.mapper.SeckillBucketMapper;
import com.geekmall.modules.marketing.mapper.SeckillItemMapper;
import com.geekmall.modules.marketing.mapper.SeckillSessionMapper;
import com.geekmall.modules.marketing.queue.SeckillGrabResult;
import com.geekmall.modules.marketing.queue.SeckillOrderQueue;
import com.geekmall.modules.marketing.queue.SeckillResultStore;
import com.geekmall.modules.product.service.ProductStockService;
import com.geekmall.modules.trade.service.TradeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 秒杀结果查询的兜底逻辑单测。
 *
 * <p>覆盖一个容易被忽略的故障面：结果写入 Redis 失败时异常被吞（不能让已成交的抢购回滚），
 * 订单其实已经落库，但用户轮询结果会一直得到「不存在」。兜底必须按
 * {@code (user_id, request_id)} 唯一索引回查订单表。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeckillServiceImpl 结果查询兜底")
class SeckillServiceImplTest {

    @Mock
    private SeckillSessionMapper seckillSessionMapper;

    @Mock
    private SeckillItemMapper seckillItemMapper;

    @Mock
    private SeckillBucketMapper seckillBucketMapper;

    @Mock
    private SeckillBucketManager bucketManager;

    @Mock
    private ProductStockService productStockService;

    @Mock
    private TradeService tradeService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectProvider<SeckillServiceImpl> selfProvider;

    @Mock
    private SeckillOrderQueue seckillOrderQueue;

    @Mock
    private SeckillResultStore seckillResultStore;

    @Mock
    private ResilienceGuard resilienceGuard;

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private SeckillServiceImpl seckillService;

    @Test
    @DisplayName("Redis 结果缺失但订单已落库时，回源订单表并返回成功")
    void shouldFallbackToOrderTableWhenRedisResultMissing() {
        when(seckillResultStore.find("req-1")).thenReturn(Optional.empty());
        when(tradeService.findOrderNoByRequestId(7L, "req-1")).thenReturn("GM202610260000000001");

        SeckillGrabResult result = seckillService.grabResult(7L, "req-1");

        assertThat(result.status()).isEqualTo(SeckillGrabResult.Status.SUCCESS);
        assertThat(result.orderNo()).isEqualTo("GM202610260000000001");
        assertThat(meterRegistry.counter("mall_seckill_result_fallback_total").count())
                .as("回源命中必须打点，否则结果写失败无法被发现")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 结果与订单都不存在时返回资源不存在")
    void shouldThrowNotFoundWhenNeitherExists() {
        when(seckillResultStore.find("req-2")).thenReturn(Optional.empty());
        when(tradeService.findOrderNoByRequestId(7L, "req-2")).thenReturn(null);

        assertThatThrownBy(() -> seckillService.grabResult(7L, "req-2"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("抢购记录不存在");
    }

    @Test
    @DisplayName("Redis 有结果时优先返回，不查订单表")
    void shouldPreferRedisResult() {
        when(seckillResultStore.find("req-3")).thenReturn(Optional.of(SeckillGrabResult.success("req-3", 7L, "GM-3")));

        SeckillGrabResult result = seckillService.grabResult(7L, "req-3");

        assertThat(result.orderNo()).isEqualTo("GM-3");
        verify(tradeService, never()).findOrderNoByRequestId(any(), any());
    }

    @Test
    @DisplayName("越权查询不回源，按不存在处理")
    void shouldNotLeakOtherUsersResult() {
        when(seckillResultStore.find("req-4")).thenReturn(Optional.of(SeckillGrabResult.success("req-4", 99L, "GM-4")));
        when(tradeService.findOrderNoByRequestId(7L, "req-4")).thenReturn(null);

        assertThatThrownBy(() -> seckillService.grabResult(7L, "req-4"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("抢购记录不存在");
    }
}
