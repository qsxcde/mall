package com.geekmall.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Key 构造单元测试。
 *
 * <p>这些字符串是跨实例、跨版本的隐式契约：一旦改动，旧 key 会变成垃圾、
 * 新 key 会读不到续期数据，因此固化断言防止误改。</p>
 */
class RedisKeysTest {

    @Test
    @DisplayName("短信验证码 key 带场景与手机号，避免不同场景互相覆盖")
    void shouldBuildSmsCodeKey() {
        assertThat(RedisKeys.smsCode("register", "13800000000"))
                .isEqualTo("mall:sms:code:register:13800000000");
        assertThat(RedisKeys.smsCode("login", "13800000000"))
                .isEqualTo("mall:sms:code:login:13800000000");
    }

    @Test
    @DisplayName("同一手机号的不同场景 key 必须不相等")
    void smsCodeKeyShouldDifferByScene() {
        assertThat(RedisKeys.smsCode("login", "13800000000"))
                .isNotEqualTo(RedisKeys.smsCode("reset", "13800000000"));
    }

    @Test
    @DisplayName("买家与商家令牌 key 前缀隔离，同 ID 也不冲突")
    void shouldIsolateBuyerAndMerchantTokens() {
        assertThat(RedisKeys.loginToken(1L)).isEqualTo("mall:login:token:1");
        assertThat(RedisKeys.merchantLoginToken(1L)).isEqualTo("mall:merchant:token:1");
        assertThat(RedisKeys.loginToken(1L)).isNotEqualTo(RedisKeys.merchantLoginToken(1L));
    }

    @Test
    @DisplayName("秒杀库存与一人一单标记前缀固定")
    void shouldExposeSeckillPrefixes() {
        assertThat(RedisKeys.SECKILL_STOCK).isEqualTo("mall:seckill:stock:");
        assertThat(RedisKeys.SECKILL_BOUGHT).isEqualTo("mall:seckill:bought:");
        assertThat(RedisKeys.SECKILL_STOCK).isNotEqualTo(RedisKeys.SECKILL_BOUGHT);
    }

    @Test
    @DisplayName("下单幂等键前缀固定")
    void shouldExposeIdempotentPrefix() {
        assertThat(RedisKeys.ORDER_IDEMPOTENT).isEqualTo("mall:order:idempotent:");
    }
}
