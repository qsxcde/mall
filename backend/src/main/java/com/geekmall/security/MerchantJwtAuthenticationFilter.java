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
import org.springframework.core.annotation.Order;
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
 * 商家端 JWT 鉴权过滤器。
 *
 * <p>只处理 {@code scope=merchant} 的令牌：解析 → 校验 Redis 会话（支持登出/踢下线）
 * → 写入带有 {@link MerchantLoginUser} principal 的认证信息。买家令牌会被直接放行，
 * 交给 {@link JwtAuthenticationFilter} 处理，两端互不干扰。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class MerchantJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                String scope = claims.get(SecurityConstants.SCOPE_CLAIM, String.class);
                if (SecurityConstants.SCOPE_MERCHANT.equals(scope)) {
                    Long merchantUserId = Long.valueOf(claims.getSubject());
                    String cached = redisTemplate.opsForValue().get(RedisKeys.merchantLoginToken(merchantUserId));
                    if (Objects.equals(cached, token)) {
                        Long shopId = claims.get(SecurityConstants.SHOP_ID_CLAIM, Number.class) == null
                                ? null
                                : claims.get(SecurityConstants.SHOP_ID_CLAIM, Number.class).longValue();
                        MerchantLoginUser principal = new MerchantLoginUser(
                                merchantUserId, claims.get("username", String.class), shopId, null);
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(new SimpleGrantedAuthority(SecurityConstants.ROLE_MERCHANT)));
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        log.debug("商家令牌已失效（已登出或被顶下线），merchantUserId={}", merchantUserId);
                    }
                }
            } catch (Exception e) {
                // 令牌非法/过期：清空上下文，交由 AuthenticationEntryPoint 统一返回 401
                log.debug("商家令牌解析失败：{}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(SecurityConstants.TOKEN_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return header.substring(SecurityConstants.TOKEN_PREFIX.length()).trim();
        }
        return null;
    }
}
