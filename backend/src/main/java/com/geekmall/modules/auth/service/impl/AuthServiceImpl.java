package com.geekmall.modules.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.common.util.JwtUtil;
import com.geekmall.modules.auth.dto.LoginDTO;
import com.geekmall.modules.auth.dto.RegisterDTO;
import com.geekmall.modules.auth.dto.ResetPasswordDTO;
import com.geekmall.modules.auth.dto.SmsLoginDTO;
import com.geekmall.modules.auth.service.AuthService;
import com.geekmall.modules.auth.service.SmsCodeService;
import com.geekmall.modules.auth.vo.LoginVO;
import com.geekmall.modules.auth.vo.SmsCodeVO;
import com.geekmall.modules.user.converter.UserConverter;
import com.geekmall.modules.user.entity.SysUser;
import com.geekmall.modules.user.mapper.SysUserMapper;
import com.geekmall.security.LoginSessionBroadcaster;
import com.geekmall.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 验证码场景，与前端调用一致 */
    public static final String SCENE_LOGIN = "login";
    public static final String SCENE_REGISTER = "register";
    public static final String SCENE_RESET = "reset";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final SmsCodeService smsCodeService;
    /** 会话失效广播（P1-6）：让其他实例立即清掉本地令牌缓存 */
    private final LoginSessionBroadcaster sessionBroadcaster;

    @Value("${mall.sms.expose-code:false}")
    private boolean exposeCode;

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUser user = findUserByAccount(dto.getAccount());
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw BizException.of(ResultCode.PASSWORD_ERROR);
        }
        return issueToken(user);
    }

    @Override
    public LoginVO smsLogin(SmsLoginDTO dto) {
        smsCodeService.verify(SCENE_LOGIN, dto.getPhone(), dto.getSmsCode());
        SysUser user = findUserByAccount(dto.getPhone());
        if (user == null) {
            // 手机号首次登录自动注册，与多数电商 App 行为一致
            user = createUser(dto.getPhone(), null);
        }
        return issueToken(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterDTO dto) {
        if (Boolean.FALSE.equals(dto.getAgreed())) {
            throw new BizException(ResultCode.PARAM_ERROR, "请先阅读并同意用户协议");
        }
        smsCodeService.verify(SCENE_REGISTER, dto.getPhone(), dto.getSmsCode());
        if (findUserByAccount(dto.getPhone()) != null) {
            throw BizException.of(ResultCode.USER_ALREADY_EXISTS);
        }
        SysUser user = createUser(dto.getPhone(), dto.getPassword());
        if (StringUtils.hasText(dto.getNickname())) {
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setNickname(dto.getNickname());
            userMapper.updateById(update);
        }
    }

    @Override
    public SmsCodeVO sendSmsCode(String phone, String scene) {
        String code = smsCodeService.send(scene, phone);
        // 仅开发环境回显验证码，方便联调；生产环境返回 null
        return new SmsCodeVO(true, 60, exposeCode ? code : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(ResetPasswordDTO dto) {
        smsCodeService.verify(SCENE_RESET, dto.getPhone(), dto.getSmsCode());
        SysUser user = findUserByAccount(dto.getPhone());
        if (user == null) {
            throw BizException.of(ResultCode.USER_NOT_FOUND);
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(update);
        // 密码已变更，强制旧令牌失效
        redisTemplate.delete(RedisKeys.loginToken(user.getId()));
        // P1-6：再广播一次，让各实例立即丢弃本地缓存的旧令牌（否则最长 30s 内仍被放行）
        sessionBroadcaster.invalidate(user.getId());
    }

    @Override
    public void logout() {
        try {
            Long userId = SecurityUtils.getUserId();
            redisTemplate.delete(RedisKeys.loginToken(userId));
            // P1-6：广播失效，登出在其他实例上立即生效（不必等本地缓存 30s 过期）
            sessionBroadcaster.invalidate(userId);
        } catch (Exception e) {
            log.debug("登出时未获取到登录态，忽略：{}", e.getMessage());
        }
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    private LoginVO issueToken(SysUser user) {
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }
        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        // 服务端留存会话，支持登出与单点登录踢下线
        redisTemplate.opsForValue().set(RedisKeys.loginToken(user.getId()), token,
                jwtUtil.getExpireSeconds(), TimeUnit.SECONDS);
        // P1-6：新会话已覆盖旧会话（单点登录语义），广播让各实例立即丢弃旧令牌的本地缓存
        sessionBroadcaster.invalidate(user.getId());
        return new LoginVO(token, "Bearer", jwtUtil.getExpireSeconds(), UserConverter.toProfile(user));
    }

    private SysUser findUserByAccount(String account) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .and(w -> w.eq(SysUser::getUsername, account).or().eq(SysUser::getPhone, account))
                .last("limit 1"));
    }

    private SysUser createUser(String phone, String rawPassword) {
        SysUser user = new SysUser();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(
                StringUtils.hasText(rawPassword) ? rawPassword : UUID.randomUUID().toString().replace("-", "")));
        user.setNickname("极客用户" + phone.substring(phone.length() - 4));
        user.setStatus(1);
        user.setLevelId(1L);
        user.setPoints(0);
        user.setGrowth(0);
        userMapper.insert(user);
        return user;
    }
}
