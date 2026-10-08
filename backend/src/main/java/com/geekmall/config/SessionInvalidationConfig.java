package com.geekmall.config;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.security.LoginTokenLocalCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 登录会话失效的 Pub/Sub 监听端。
 *
 * <p>与 {@code LoginSessionBroadcaster}（发布端）配对：订阅
 * {@link RedisKeys#SESSION_INVALIDATION_CHANNEL}，收到 userId 后清掉本实例的令牌缓存。</p>
 *
 * <p>由此把「登出 / 顶下线 / 改密」的跨实例生效延迟从本地缓存的 30s 降到毫秒级；
 * Pub/Sub 不可用（如托管 Redis 禁用了 pub/sub）时，30s TTL 仍是兜底，不会导致鉴权错误。</p>
 */
@Slf4j
@Configuration
public class SessionInvalidationConfig {

    @Bean
    public RedisMessageListenerContainer sessionInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory, LoginTokenLocalCache localCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Message.getBody() 是原始字节（容器不对消息体做反序列化），因此直接按 UTF-8 解析
        container.addMessageListener((message, pattern) -> {
            Long userId = parseUserId(message.getBody());
            if (userId != null) {
                localCache.invalidate(userId);
            }
        }, new ChannelTopic(RedisKeys.SESSION_INVALIDATION_CHANNEL));
        log.info("已订阅登录会话失效频道：{}", RedisKeys.SESSION_INVALIDATION_CHANNEL);
        return container;
    }

    /** 消息体是 userId 的十进制字符串；非法消息忽略即可，不应影响其他实例。 */
    private static Long parseUserId(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            log.warn("收到无法解析的会话失效广播，已忽略：{}", text);
            return null;
        }
    }
}
