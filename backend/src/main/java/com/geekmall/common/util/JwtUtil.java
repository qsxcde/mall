package com.geekmall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发与解析。
 *
 * <p>注意 secret 长度须 >= 32 字节（HS256 要求），生产环境务必通过环境变量覆盖。</p>
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${mall.jwt.secret}")
    private String secret;

    @Value("${mall.jwt.expire-minutes:120}")
    private long expireMinutes;

    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("mall.jwt.secret 长度必须不少于 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        log.info("JWT 初始化完成，令牌有效期 {} 分钟", expireMinutes);
    }

    public long getExpireSeconds() {
        return expireMinutes * 60;
    }

    public String createToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMinutes * 60_000L))
                .signWith(key)
                .compact();
    }

    /**
     * 签发带作用域的令牌（商家端 / 买家端共用同一密钥，靠 scope 区分）。
     *
     * @param id      主体 ID（买家为 userId，商家为 merchantUserId）
     * @param username 账号名
     * @param scope   作用域名，见 {@link com.geekmall.common.constant.SecurityConstants#SCOPE_MERCHANT}
     * @param shopId  店铺 ID，仅商家令牌携带，便于鉴权时免查库
     */
    public String createToken(Long id, String username, String scope, Long shopId) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(String.valueOf(id))
                .claim("username", username)
                .claim(com.geekmall.common.constant.SecurityConstants.SCOPE_CLAIM, scope)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMinutes * 60_000L));
        if (shopId != null) {
            builder.claim(com.geekmall.common.constant.SecurityConstants.SHOP_ID_CLAIM, shopId);
        }
        return builder.signWith(key).compact();
    }

    /** 解析令牌，非法或过期会抛出 JwtException。 */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }
}
