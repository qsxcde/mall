package com.geekmall.modules.auth.service;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 短信验证码服务。
 *
 * <p>验证码存 Redis（5 分钟有效），发送侧做 60 秒冷却防刷。
 * 真实的短信通道只需替换 {@link #doSend}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    /** 验证码有效期 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 发送冷却时间 */
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    /**
     * 生成并“发送”验证码。
     *
     * @return 生成的验证码（是否回显给调用方由上层决定）
     */
    public String send(String scene, String phone) {
        String cooldownKey = RedisKeys.SMS_CODE + "limit:" + phone;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", SEND_COOLDOWN);
        if (Boolean.FALSE.equals(acquired)) {
            throw new BizException(ResultCode.BIZ_ERROR, "验证码发送过于频繁，请 60 秒后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        redisTemplate.opsForValue().set(RedisKeys.smsCode(scene, phone), code, CODE_TTL);
        doSend(phone, code);
        return code;
    }

    /**
     * 校验并消费验证码（校验成功后立即失效，防止重复使用）。
     */
    public void verify(String scene, String phone, String code) {
        String key = RedisKeys.smsCode(scene, phone);
        String cached = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(cached) || !cached.equals(code)) {
            throw BizException.of(ResultCode.SMS_CODE_ERROR);
        }
        redisTemplate.delete(key);
    }

    /** 模拟短信通道：生产环境替换为真实短信服务商调用。 */
    private void doSend(String phone, String code) {
        log.info("[模拟短信] 向 {} 发送验证码 {}", maskPhone(phone), code);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
