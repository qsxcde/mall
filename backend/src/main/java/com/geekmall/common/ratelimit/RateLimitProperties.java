package com.geekmall.common.ratelimit;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流配置项（{@code mall.rate-limit.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.rate-limit")
public class RateLimitProperties {

    /**
     * 总开关。
     *
     * <p>功能回归测试里会把它关掉：那些用例验证的是业务流程，而限流会在
     * 「同一个 IP 连续登录几十次」这类场景下把测试打断，造成与业务无关的红灯。
     * 限流自身的行为由专门的用例在开启状态下验证。</p>
     */
    private boolean enabled = true;

    /** 本地限流器保留的窗口上限，防止被随机 key 撑爆内存。 */
    private int localMaxKeys = 200_000;

    /**
     * 可信反向代理的 CIDR 列表（如 {@code 10.0.0.0/8}）。
     *
     * <p><b>默认为空：不信任任何转发头</b>。只有请求的 {@code remoteAddr}
     * 命中该列表时，才解析 {@code X-Forwarded-For / X-Real-IP}；
     * 否则客户端可以随意伪造这些头来绕过按 IP 的限流。</p>
     */
    private List<String> trustedProxies = new ArrayList<>();
}
