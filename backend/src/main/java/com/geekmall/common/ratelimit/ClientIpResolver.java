package com.geekmall.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 解析（可信代理感知）。
 *
 * <p><b>为什么不能直接取 X-Forwarded-For</b>：该头由客户端完全可控。若应用直接暴露，
 * 攻击者每次请求换一个伪造 IP，就能绕过所有按 IP 的限流（登录、发码、注册）。
 * 因此只有请求确实来自可信代理时，才解析转发头。</p>
 *
 * <p>多级代理时从右往左跳过可信代理，取第一个不可信地址：最右侧是离应用最近的代理，
 * 客户端伪造的左侧条目不会被误信。</p>
 */
@Slf4j
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(RateLimitProperties properties) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : properties.getTrustedProxies()) {
            if (StringUtils.hasText(cidr)) {
                matchers.add(new IpAddressMatcher(cidr.trim()));
            }
        }
        this.trustedProxies = List.copyOf(matchers);
    }

    /** 解析真实客户端 IP；无可信代理配置时永远返回 {@code remoteAddr}。 */
    public String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (trustedProxies.isEmpty() || !isTrusted(remote)) {
            // 直接暴露或来源不可信：忽略所有转发头
            return remote;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = normalize(parts[i]);
                if (StringUtils.hasText(candidate) && !isTrusted(candidate)) {
                    return candidate;
                }
            }
            // 整条链都是可信代理：退回最左侧地址（至少比取网关 IP 更接近真实客户端）
            for (String part : parts) {
                String candidate = normalize(part);
                if (StringUtils.hasText(candidate)) {
                    return candidate;
                }
            }
        }

        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (StringUtils.hasText(realIp) && !isTrusted(realIp)) {
            return realIp;
        }
        return remote;
    }

    private boolean isTrusted(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 转发头里可能出现非 IP 字符串（如 unknown）：按不可信处理即可
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
