package com.geekmall.modules.marketing.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.modules.marketing.config.SeckillProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 秒杀抢购结果的存储与查询（异步模式的必备配套）。
 *
 * <p>没有它，异步化就是半成品：请求返回了「排队中」，但用户永远拿不到订单号。</p>
 *
 * <p>结果与幂等标记都放在 Redis：它们是**短生命周期的过程状态**，
 * 不是业务数据，不该占用 MySQL 表与迁移脚本。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillResultStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SeckillProperties properties;

    /** 写入/覆盖结果（先写 QUEUED，消费端再覆盖为终态）。 */
    public void save(SeckillGrabResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(RedisKeys.seckillResult(result.requestId()), json,
                    properties.getAsync().getResultTtl());
        } catch (Exception ex) {
            // 写结果失败不让主流程失败：最坏情况是用户轮询不到结果（订单其实已建），
            // 但绝不能因为一次 Redis 抖动把已成功的抢购回滚掉
            log.error("写入秒杀结果失败：requestId={}, status={}",
                    result.requestId(), result.status(), ex);
        }
    }

    /** 查询结果；不存在（未受理或已过期）返回空。 */
    public Optional<SeckillGrabResult> find(String requestId) {
        String json = redisTemplate.opsForValue().get(RedisKeys.seckillResult(requestId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SeckillGrabResult.class));
        } catch (Exception ex) {
            log.warn("秒杀结果反序列化失败：requestId={}", requestId, ex);
            return Optional.empty();
        }
    }
}
