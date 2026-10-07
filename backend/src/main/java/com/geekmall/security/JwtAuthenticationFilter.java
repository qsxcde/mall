package com.geekmall.security;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.common.util.JwtUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.time.Duration;
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
     * 登录令牌本地短缓存（P1-2）。
     *
     * <p>此前每个带令牌的请求都要同步访问一次 Redis；这里加 30s 本地缓存，
     * 命中后完全不走网络，把鉴权路径的 Redis QPS 降一个数量级。
     * 代价是登出/踢下线最多延迟 30s 生效，属于可接受的权衡。</p>
     *
     * <p><b>缓存键必须是令牌本身，不能是 userId。</b>按 userId 缓存时会有一个致命缺陷：
     * 同一用户在 30s 内重新登录，Redis 已被写入新令牌，但本地缓存仍持有旧令牌且命中后
     * <b>不会回源 Redis</b>，于是刚签发的新令牌被判为失效 —— 表现为「登录成功却满屏 401」，
     * 前端 401 处理会清令牌跳登录页，形成最长 30s 的登录死循环。
     * 以令牌为键后，新令牌天然是缓存未命中，会回源校验，行为正确。</p>
     */
    private final Cache<String, LoginUser> tokenCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

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
