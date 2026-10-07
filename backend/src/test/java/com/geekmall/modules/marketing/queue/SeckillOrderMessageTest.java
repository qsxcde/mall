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
 */
@DisplayName("SeckillOrderMessage 序列化")
class SeckillOrderMessageTest {

    @Test
    @DisplayName("字段往返一致")
    void shouldRoundTripFields() {
        SeckillOrderMessage message = new SeckillOrderMessage("req-abc", 8L, 100L, 200L);

        Map<Object, Object> fields = new HashMap<>();
        fields.putAll(message.toFields());

        assertThat(SeckillOrderMessage.fromFields(fields)).isEqualTo(message);
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
