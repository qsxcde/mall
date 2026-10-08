package com.geekmall.security;

import com.geekmall.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录会话失效广播（发布端）。
 *
 * <p>登出、顶下线、改密都会让服务端会话令牌失效。而鉴权路径有 30s 本地缓存，
 * 若不广播，其他实例最长 30s 内仍会认为旧令牌有效 —— 安全上这是「登出后旧令牌还能用」，
 * 用户体验上则是「改完密码另一台设备还能继续操作」。</p>
 *
 * <p>这里通过 Redis Pub/Sub 把 userId 广播出去，各实例收到后立即清掉本地缓存
 * （见 {@code SessionInvalidationConfig} 的监听器）。</p>
 *
 * <p><b>失败不影响主流程</b>：广播是可选的加速手段，发不出去时仍有 30s TTL 兜底，
 * 绝不能因为一次 Redis 抖动让「登出」本身失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginSessionBroadcaster {

    private final StringRedisTemplate redisTemplate;

    /** 广播「该用户的登录令牌已变更或失效」，让所有实例立即清掉本地缓存。 */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(RedisKeys.SESSION_INVALIDATION_CHANNEL, String.valueOf(userId));
            log.debug("已广播登录会话失效：userId={}", userId);
        } catch (Exception ex) {
            log.warn("广播登录会话失效失败（各实例本地缓存将由 30s TTL 兜底）：userId={}, error={}",
                    userId, ex.getMessage());
        }
    }
}
