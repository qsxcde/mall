package com.geekmall.config;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.security.LoginSessionBroadcaster;
import com.geekmall.security.LoginTokenLocalCache;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 登录会话失效的 Pub/Sub 监听端。
 *
 * <p>与 {@code LoginSessionBroadcaster}（发布端）配对：订阅
 * {@link RedisKeys#SESSION_INVALIDATION_CHANNEL}，收到载荷后清掉本实例的令牌缓存。</p>
 *
 * <p>载荷有两种形态：{@code userId}（登出/改密，清掉该用户全部缓存）与
 * {@code userId|newToken}（登录顶下线，只清旧令牌）。后者是必要的：广播是异步的，
 * 若无条件失效，刚建立的新令牌缓存会被自己的登录广播清掉。</p>
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
        container.addMessageListener(
                (message, pattern) -> {
                    String payload = bodyOf(message.getBody());
                    Long userId = LoginSessionBroadcaster.parseUserId(payload);
                    if (userId == null) {
                        log.warn("收到无法解析的会话失效广播，已忽略：{}", payload);
                        return;
                    }
                    String newToken = LoginSessionBroadcaster.parseNewToken(payload);
                    if (newToken == null) {
                        // 登出 / 改密：该用户所有令牌缓存都应立即失效
                        localCache.invalidate(userId);
                    } else {
                        // 登录顶下线：只剔除旧令牌，新令牌自己不能被这条异步广播误清
                        localCache.invalidatePrevious(userId, newToken);
                    }
                },
                new ChannelTopic(RedisKeys.SESSION_INVALIDATION_CHANNEL));
        log.info("已订阅登录会话失效频道：{}", RedisKeys.SESSION_INVALIDATION_CHANNEL);
        return container;
    }

    /** 消息体是 UTF-8 文本；空消息返回 null。 */
    private static String bodyOf(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        return new String(body, StandardCharsets.UTF_8).trim();
    }
}
