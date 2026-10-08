package com.geekmall.modules.marketing.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀队列消息体单元测试。
 *
 * <p>消息是生产端与消费端之间唯一的契约，序列化出错会让整条削峰链路失效，
 * 因此两组断言都要有：正常往返，以及**字段损坏时必须显式失败**。</p>
 *
 * <p>另外单独覆盖 {@code traceId} 的「可选」语义：它是后来新增的观测字段，
 * 必须保证老消息（没有该字段）仍能被解析，否则一次部署就会把在途消息全变成毒消息。</p>
 */
@DisplayName("SeckillOrderMessage 序列化")
class SeckillOrderMessageTest {

    @Test
    @DisplayName("字段往返一致")
    void shouldRoundTripFields() {
        SeckillOrderMessage message = new SeckillOrderMessage("req-abc", 8L, 100L, 200L, "trace-001");

        Map<Object, Object> fields = new HashMap<>();
        fields.putAll(message.toFields());

        assertThat(SeckillOrderMessage.fromFields(fields)).isEqualTo(message);
    }

    @Test
    @DisplayName("分桶消息：bucketNo 往返一致")
    void shouldRoundTripBucketNo() {
        SeckillOrderMessage message = new SeckillOrderMessage("req-abc", 8L, 100L, 200L, "trace-001", 3);

        assertThat(SeckillOrderMessage.fromFields(new HashMap<>(message.toFields())))
                .isEqualTo(message);
        assertThat(message.toFields()).containsEntry("bucketNo", "3");
    }

    @Test
    @DisplayName("不分桶消息：bucketNo 为 null 且不落字段（否则消费端会去扣一个不存在的桶）")
    void shouldNotWriteNullBucketNo() {
        SeckillOrderMessage message = new SeckillOrderMessage("req-abc", 8L, 100L, 200L, "trace-001");

        assertThat(message.bucketNo()).isNull();
        assertThat(message.toFields()).doesNotContainKey("bucketNo");
        assertThat(SeckillOrderMessage.fromFields(new HashMap<>(message.toFields())).bucketNo()).isNull();
    }

    @Test
    @DisplayName("缺少 bucketNo 的老消息仍可解析")
    void shouldTolerateMissingBucketNo() {
        Map<Object, Object> legacy = new HashMap<>();
        legacy.put("requestId", "req-abc");
        legacy.put("userId", "8");
        legacy.put("itemId", "100");
        legacy.put("addressId", "200");

        assertThat(SeckillOrderMessage.fromFields(legacy).bucketNo()).isNull();
    }

    @Test
    @DisplayName("缺少 traceId 的老消息仍可解析，且不写入空字段")
    void shouldTolerateMissingTraceId() {
        Map<Object, Object> legacy = new HashMap<>();
        legacy.put("requestId", "req-abc");
        legacy.put("userId", "8");
        legacy.put("itemId", "100");
        legacy.put("addressId", "200");

        SeckillOrderMessage message = SeckillOrderMessage.fromFields(legacy);

        assertThat(message.traceId()).as("观测字段缺失不应导致消息失败").isNull();
        assertThat(message.requestId()).isEqualTo("req-abc");
        assertThat(message.toFields()).as("空 traceId 不落字段，保持消息精简").doesNotContainKey("traceId");
    }

    @Test
    @DisplayName("traceId 为空白时同样不落字段")
    void shouldNotWriteBlankTraceId() {
        SeckillOrderMessage message = new SeckillOrderMessage("req-abc", 8L, 100L, 200L, "  ");

        assertThat(message.toFields()).doesNotContainKey("traceId");
        assertThat(SeckillOrderMessage.fromFields(new HashMap<>(message.toFields())).traceId()).isNull();
    }

    @Test
    @DisplayName("字段缺失时抛异常，而不是静默返回残缺消息")
    void shouldRejectIncompleteFields() {
        Map<Object, Object> broken = new HashMap<>();
        broken.put("requestId", "req-abc");
        broken.put("itemId", "100");

        assertThatThrownBy(() -> SeckillOrderMessage.fromFields(broken))
                .as("缺字段的消息必须显式失败，否则用户会永远查不到结果")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("字段值非法时同样抛异常（交给消费端按毒消息丢弃）")
    void shouldRejectNonNumericUserId() {
        Map<Object, Object> broken = new HashMap<>();
        broken.put("requestId", "req-abc");
        broken.put("userId", "not-a-number");
        broken.put("itemId", "100");
        broken.put("addressId", "200");

        assertThatThrownBy(() -> SeckillOrderMessage.fromFields(broken))
                .isInstanceOf(NumberFormatException.class);
    }
}
