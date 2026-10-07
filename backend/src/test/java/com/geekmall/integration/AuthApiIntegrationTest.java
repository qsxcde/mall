package com.geekmall.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.result.ResultCode;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证接口集成测试（MockMvc + 真实 MySQL/Redis + 真实过滤器链）。
 *
 * <p>验证的是「接口约定」而不只是方法逻辑：统一响应体、业务错误码、
 * 白名单放行、以及 Redis 单点会话下的令牌失效。</p>
 */
class AuthApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("注册 → 登录 → 拉取个人资料：全链路打通且昵称沿用注册值")
    void shouldRegisterLoginAndFetchProfile() {
        String phone = randomPhone();

        JsonNode sms = assertSuccess(post("/api/v1/auth/sms-code",
                Map.of("phone", phone, "scene", "register"), null));
        String code = sms.get("devCode").asText();
        assertThat(code).matches("\\d{6}");

        assertSuccess(post("/api/v1/auth/register", Map.of(
                "phone", phone,
                "smsCode", code,
                "password", "pass123456",
                "nickname", "集成测试用户",
                "agreed", true), null));

        String token = loginBuyer(phone, "pass123456");
        assertThat(token).isNotBlank();

        JsonNode profile = assertSuccess(get("/api/v1/user/profile", token));
        assertThat(profile.get("nickname").asText()).isEqualTo("集成测试用户");
        assertThat(profile.get("phone").asText()).isEqualTo(phone);
        assertThat(profile.get("levelId").asLong()).isEqualTo(1L);
        assertThat(profile.get("points").asInt()).isZero();
    }

    @Test
    @DisplayName("注册响应体符合统一约定：code=0 / msg=success")
    void registerResponseShouldFollowContract() {
        String phone = randomPhone();
        seedSmsCode("register", phone, "123456");

        JsonNode response = post("/api/v1/auth/register", Map.of(
                "phone", phone,
                "smsCode", "123456",
                "password", "pass123456",
                "agreed", true), null);

        assertThat(response.get("code").asInt()).isZero();
        assertThat(response.get("msg").asText()).isEqualTo("success");
    }

    @Test
    @DisplayName("重复手机号注册被拒绝，返回「账号或手机号已被注册」")
    void shouldRejectDuplicatePhone() {
        String phone = randomPhone();
        seedSmsCode("register", phone, "111111");
        assertSuccess(post("/api/v1/auth/register", Map.of(
                "phone", phone, "smsCode", "111111", "password", "pass123456", "agreed", true), null));

        seedSmsCode("register", phone, "222222");
        JsonNode response = post("/api/v1/auth/register", Map.of(
                "phone", phone, "smsCode", "222222", "password", "pass123456", "agreed", true), null);

        assertThat(response.get("code").asInt())
                .isEqualTo(ResultCode.USER_ALREADY_EXISTS.getCode());
    }

    @Test
    @DisplayName("验证码错误时注册失败，返回验证码错误码")
    void shouldRejectWrongSmsCode() {
        String phone = randomPhone();
        seedSmsCode("register", phone, "123456");

        JsonNode response = post("/api/v1/auth/register", Map.of(
                "phone", phone, "smsCode", "000000", "password", "pass123456", "agreed", true), null);

        assertThat(response.get("code").asInt())
                .isEqualTo(ResultCode.SMS_CODE_ERROR.getCode());
    }

    @Test
    @DisplayName("验证码一次性消费：同一个码不能用两次")
    void smsCodeShouldBeSingleUse() {
        String phone = randomPhone();
        seedSmsCode("register", phone, "654321");
        assertSuccess(post("/api/v1/auth/register", Map.of(
                "phone", phone, "smsCode", "654321", "password", "pass123456", "agreed", true), null));

        String otherPhone = randomPhone();
        JsonNode response = post("/api/v1/auth/register", Map.of(
                "phone", otherPhone, "smsCode", "654321", "password", "pass123456", "agreed", true), null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.SMS_CODE_ERROR.getCode());
    }

    @Test
    @DisplayName("未勾选用户协议时注册被拒绝")
    void shouldRejectWithoutAgreement() {
        String phone = randomPhone();
        seedSmsCode("register", phone, "123456");

        JsonNode response = post("/api/v1/auth/register", Map.of(
                "phone", phone, "smsCode", "123456", "password", "pass123456", "agreed", false), null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("密码错误时登录被拒绝，且不泄露账号是否存在")
    void shouldRejectWrongPassword() {
        JsonNode response = post("/api/v1/auth/login",
                Map.of("account", DEMO_BUYER, "password", "wrong-password"), null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.PASSWORD_ERROR.getCode());
    }

    @Test
    @DisplayName("参数校验失败（密码过短）返回 400 与具体原因")
    void shouldRejectInvalidPayload() {
        JsonNode response = post("/api/v1/auth/login",
                Map.of("account", DEMO_BUYER, "password", "123"), null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
        assertThat(response.get("msg").asText()).contains("密码长度");
    }

    @Test
    @DisplayName("短信验证码登录：新手机号自动注册并签发令牌")
    void smsLoginShouldAutoRegister() {
        String phone = randomPhone();
        JsonNode sms = assertSuccess(post("/api/v1/auth/sms-code",
                Map.of("phone", phone, "scene", "login"), null));
        String code = sms.get("devCode").asText();

        JsonNode login = assertSuccess(post("/api/v1/auth/login/sms",
                Map.of("phone", phone, "smsCode", code), null));
        String token = login.get("token").asText();
        assertThat(token).isNotBlank();

        JsonNode profile = assertSuccess(get("/api/v1/user/profile", token));
        assertThat(profile.get("phone").asText()).isEqualTo(phone);
    }

    @Test
    @DisplayName("未登录访问受保护接口返回 code 401（HTTP 仍为 200）")
    void shouldRejectAnonymousAccess() {
        JsonNode response = get("/api/v1/user/profile", null);

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("伪造令牌同样按未登录处理")
    void shouldRejectForgedToken() {
        JsonNode response = get("/api/v1/user/profile", "not-a-real-token");

        assertThat(response.get("code").asInt()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("白名单接口匿名可访问：商品列表无需登录")
    void whitelistShouldAllowAnonymous() {
        JsonNode data = assertSuccess(get("/api/v1/products?page=1&pageSize=2", null));

        assertThat(data.has("list")).isTrue();
        assertThat(data.has("total")).isTrue();
    }

    @Test
    @DisplayName("白名单接口匿名可访问：分类树与首页楼层")
    void whitelistContentEndpoints() {
        assertThat(get("/api/v1/categories/tree", null).get("code").asInt()).isZero();
        assertThat(get("/api/v1/home/floors", null).get("code").asInt()).isZero();
        assertThat(get("/api/v1/cms/about", null).get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("单点会话：会话被其他设备替换后，旧令牌立即失效")
    void shouldEnforceSingleSession() {
        String phone = randomPhone();
        String staleToken = registerAndLogin(phone);

        // 注意：不能通过「连续两次登录」来构造新令牌 —— JWT 的时间戳精度为秒，
        // 同一秒内为同一用户签发的令牌是字节相同的，旧令牌不会真的失效。
        // 因此这里直接替换 Redis 中的会话令牌，等价于「在另一台设备登录」。
        JsonNode login = assertSuccess(post("/api/v1/auth/login",
                Map.of("account", phone, "password", DEMO_PASSWORD), null));
        long userId = login.get("user").get("id").asLong();
        stringRedisTemplate.opsForValue()
                .set(RedisKeys.loginToken(userId), "token-issued-on-another-device");

        JsonNode stale = get("/api/v1/user/profile", staleToken);
        assertThat(stale.get("code").asInt())
                .as("会话已被替换，旧令牌必须被判为失效")
                .isEqualTo(ResultCode.UNAUTHORIZED.getCode());

        // 把会话恢复为新令牌，证明失效判定依据的是 Redis 中的会话值，而不是把旧令牌拉黑
        String freshToken = login.get("token").asText();
        stringRedisTemplate.opsForValue().set(RedisKeys.loginToken(userId), freshToken);
        assertThat(get("/api/v1/user/profile", freshToken).get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("登出接口正常返回成功")
    void logoutShouldSucceed() {
        String token = registerAndLogin(randomPhone());

        JsonNode response = post("/api/v1/auth/logout", null, token);

        assertThat(response.get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("重置密码后可以用新密码登录")
    void shouldResetPassword() {
        String phone = randomPhone();
        registerAndLogin(phone);

        seedSmsCode("reset", phone, "888888");
        assertSuccess(post("/api/v1/auth/password/reset", Map.of(
                "phone", phone,
                "smsCode", "888888",
                "newPassword", "newpass123"), null));

        assertThat(loginBuyer(phone, "newpass123")).isNotBlank();
    }
}
