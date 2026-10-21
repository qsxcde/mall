package com.geekmall.modules.auth.service;

import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 短信验证码服务。
 *
 * <p>验证码存 Redis（5 分钟有效），发送侧做 60 秒冷却与每日上限；
 * 校验侧用 Lua 原子完成「比较 → 失败计数 → 成功删除」，避免并发重放，
 * 并在连续输错后锁定该手机号，防止 6 位验证码被暴力枚举。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    /** 验证码有效期 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 发送冷却时间 */
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    /** 同一手机号每日最多发送次数 */
    private static final int MAX_SEND_PER_DAY = 10;
    /** 每日发送计数 TTL */
    private static final Duration SEND_DAY_TTL = Duration.ofHours(24);
    /** 连续输错上限 */
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    /** 输错锁定时间 */
    private static final Duration VERIFY_LOCK_TTL = Duration.ofMinutes(5);

    /**
     * 原子校验脚本。
     *
     * <p>KEYS[1]=验证码 key，KEYS[2]=失败计数 key；ARGV[1]=用户输入，
     * ARGV[2]=最大失败次数，ARGV[3]=失败计数 TTL（秒）。</p>
     *
     * <p>返回值：1 成功；0 验证码错误；-1 验证码不存在/已过期；-2 已锁定。</p>
     */
    private static final RedisScript<Long> VERIFY_SCRIPT = RedisScript.of(
            """
            local cached = redis.call('GET', KEYS[1])
            if not cached then return -1 end
            local fails = tonumber(redis.call('GET', KEYS[2]) or '0')
            if fails >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1])
                return -2
            end
            if cached ~= ARGV[1] then
                local n = redis.call('INCR', KEYS[2])
                if n == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
                return 0
            end
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """,
            Long.class);

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

        String dailyKey = RedisKeys.SMS_CODE + "daily:" + phone;
        Long sentToday = redisTemplate.opsForValue().increment(dailyKey);
        if (sentToday != null && sentToday == 1L) {
            redisTemplate.expire(dailyKey, SEND_DAY_TTL);
        }
        if (sentToday == null || sentToday > MAX_SEND_PER_DAY) {
            throw new BizException(ResultCode.BIZ_ERROR, "今日验证码发送次数已达上限，请明天再试");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        redisTemplate.opsForValue().set(RedisKeys.smsCode(scene, phone), code, CODE_TTL);
        // 新码下发后重置上一轮的失败计数
        redisTemplate.delete(failKey(scene, phone));
        doSend(phone, scene);
        return code;
    }

    /**
     * 校验并消费验证码（校验成功后立即失效，防止重复使用）。
     */
    public void verify(String scene, String phone, String code) {
        if (code == null || code.isBlank()) {
            throw BizException.of(ResultCode.SMS_CODE_ERROR);
        }
        Long result = redisTemplate.execute(
                VERIFY_SCRIPT,
                List.of(RedisKeys.smsCode(scene, phone), failKey(scene, phone)),
                code,
                String.valueOf(MAX_VERIFY_ATTEMPTS),
                String.valueOf(VERIFY_LOCK_TTL.toSeconds()));
        if (result == null || result == -1L || result == 0L) {
            throw BizException.of(ResultCode.SMS_CODE_ERROR);
        }
        if (result == -2L) {
            throw new BizException(ResultCode.BIZ_ERROR, "验证码错误次数过多，请 5 分钟后再试");
        }
    }

    /** 模拟短信通道：生产环境替换为真实短信服务商调用。 */
    private void doSend(String phone, String scene) {
        // 刻意不打印验证码：日志会被集中采集，验证码一旦落日志等于失去第二因子
        log.info("[模拟短信] 向 {} 发送验证码（场景 {}）", maskPhone(phone), scene);
    }

    private String failKey(String scene, String phone) {
        return RedisKeys.SMS_CODE + "fail:" + scene + ":" + phone;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
