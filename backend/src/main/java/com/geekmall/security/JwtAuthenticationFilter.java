package com.geekmall.security;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * JWT 鉴权过滤器。
 *
 * <p>解析请求头中的令牌 → 校验 Redis 会话是否有效（支持登出 / 单点登录踢下线）
 * → 写入 SecurityContext。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    /**
     * 登录令牌本地短缓存（P1-2 / P1-6）。
     *
     * <p>此前每个带令牌的请求都要同步访问一次 Redis；本地缓存命中后完全不走网络，
     * 把鉴权路径的 Redis QPS 降一个数量级。键与失效语义封装在
     * {@link LoginTokenLocalCache}：以<b>令牌</b>为键（避免「同一用户 30s 内重登导致新令牌
     * 被判失效」的 401 死循环），同时支持按 userId 失效，供登出 / 顶下线 / 改密
     * 通过 Redis Pub/Sub 广播后<b>跨实例立即生效</b>。</p>
     */
    private final LoginTokenLocalCache tokenCache;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            try {
                // 以令牌为键：命中即该令牌在 30s 内校验通过；未命中则回源 Redis 校验。
                // loader 返回 null（令牌已失效）时 Caffeine 不会缓存空值，下次仍会回源。
                LoginUser loginUser = tokenCache.get(token, this::verifyAgainstRedis);
                if (loginUser != null) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            loginUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    log.debug("令牌已失效（已登出或被顶下线）");
                }
            } catch (Exception e) {
                // 令牌非法/过期：不抛异常，交由后续的 AuthenticationEntryPoint 统一返回 401
                log.debug("令牌解析失败：{}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 回源校验：解析令牌 → 比对 Redis 中该用户的当前会话令牌。
     * 只有二者一致才返回登录态，否则返回 null（不缓存）。
     */
    private LoginUser verifyAgainstRedis(String token) {
        Claims claims = jwtUtil.parseToken(token);
        Long userId = Long.valueOf(claims.getSubject());
        String sessionToken = redisTemplate.opsForValue().get(RedisKeys.loginToken(userId));
        if (!Objects.equals(sessionToken, token)) {
            return null;
        }
        return new LoginUser(userId, claims.get("username", String.class), null);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(SecurityConstants.TOKEN_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return header.substring(SecurityConstants.TOKEN_PREFIX.length()).trim();
        }
        return null;
    }
}
