package com.geekmall.modules.auth.service;

import com.geekmall.modules.auth.dto.LoginDTO;
import com.geekmall.modules.auth.dto.RegisterDTO;
import com.geekmall.modules.auth.dto.ResetPasswordDTO;
import com.geekmall.modules.auth.dto.SmsLoginDTO;
import com.geekmall.modules.auth.vo.LoginVO;
import com.geekmall.modules.auth.vo.SmsCodeVO;

/**
 * 认证服务：注册、登录、找回密码、登出。
 */
public interface AuthService {

    /** 密码登录。 */
    LoginVO login(LoginDTO dto);

    /** 短信验证码登录（手机号未注册时自动创建账号）。 */
    LoginVO smsLogin(SmsLoginDTO dto);

    /** 注册新账号。 */
    void register(RegisterDTO dto);

    /** 发送短信验证码。 */
    SmsCodeVO sendSmsCode(String phone, String scene);

    /** 重置密码。 */
    void resetPassword(ResetPasswordDTO dto);

    /** 登出：删除服务端会话，令牌立即失效。 */
    void logout();
}
