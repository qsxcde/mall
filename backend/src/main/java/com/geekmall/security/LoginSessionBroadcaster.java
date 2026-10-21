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

    /** 载荷分隔符：JWT 由 base64url 与 `.` 组成，不含 `|`，因此可安全用作分隔符。 */
    static final String SEPARATOR = "|";

    private final StringRedisTemplate redisTemplate;

    /**
     * 广播「该用户的登录令牌已失效」，让所有实例立即清掉本地缓存。
     *
     * <p>用于登出与改密：此时不需要保留任何令牌的缓存。</p>
     */
    public void invalidate(Long userId) {
        send(payloadOf(userId), "登录会话失效", userId);
    }

    /**
     * 广播「该用户已换新令牌」，只让各实例清掉<b>旧令牌</b>的缓存。
     *
     * <p>用于登录成功时的顶下线：新会话覆盖旧会话，旧令牌必须立即不可用；
     * 但新令牌自己不该被这次广播清掉 —— 广播是异步的，可能在新令牌已写入本地缓存之后
     * 才送达，无条件失效会让刚建立的缓存立刻作废（同实例表现为「登录后的首屏请求仍回源 Redis」）。</p>
     */
    public void invalidatePrevious(Long userId, String newToken) {
        if (newToken == null) {
            invalidate(userId);
            return;
        }
        send(payloadOf(userId) + SEPARATOR + newToken, "登录会话被新令牌顶下线", userId);
    }

    private void send(String payload, String action, Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(RedisKeys.SESSION_INVALIDATION_CHANNEL, payload);
            log.debug("已广播{}：userId={}", action, userId);
        } catch (Exception ex) {
            log.warn("广播{}失败（各实例本地缓存将由 30s TTL 兜底）：userId={}, error={}", action, userId, ex.getMessage());
        }
    }

    private static String payloadOf(Long userId) {
        return String.valueOf(userId);
    }

    /**
     * 解析载荷中的 userId；无法解析返回 null。
     *
     * <p>公开给监听端（{@code SessionInvalidationConfig}）复用，保证收发两端共用同一份编解码契约。</p>
     */
    public static Long parseUserId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        int index = payload.indexOf(SEPARATOR);
        String userIdText = index < 0 ? payload : payload.substring(0, index);
        try {
            return Long.valueOf(userIdText.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析载荷中的新令牌；载荷只带 userId（登出/改密）时返回 null。
     *
     * <p>{@code null} 表示「该用户所有令牌都失效」，非 {@code null} 表示「只失效非该令牌的旧会话」。</p>
     */
    public static String parseNewToken(String payload) {
        if (payload == null) {
            return null;
        }
        int index = payload.indexOf(SEPARATOR);
        if (index < 0 || index == payload.length() - 1) {
            return null;
        }
        return payload.substring(index + 1);
    }
}
