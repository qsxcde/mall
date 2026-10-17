package com.geekmall.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产环境配置自检。
 *
 * <p>在 {@code prod} profile 下，把「能启动但会出事故」的配置<b>直接变成启动失败</b>，
 * 而不是留一行日志等人去翻。</p>
 *
 * <h3>为什么需要它</h3>
 * <p>{@code application.yml} 里 JWT 密钥带了一串兜底默认值：
 * {@code ${MALL_JWT_SECRET:geek-mall-jwt-secret-key-please-change-in-production-2026}}。
 * 这串值已经在源码里公开 —— 只要生产忘了注入环境变量，服务照样能启动，
 * 但任何读过仓库的人都能用它对<b>任意 userId</b> 签发合法令牌。
 * 这类「默认值即漏洞」的问题不会报错、不会告警，只会在某天被发现时已经晚了。</p>
 *
 * <p>同理，演示账号、mock 接口、验证码回显这些 dev 便利开关，
 * 一旦在生产被打开就是「可被登录的公开账号」或「可绕过验证码」。</p>
 *
 * <h3>为什么不直接在 application-prod.yml 写死</h3>
 * <p>那里已经写死了（见 {@code application-prod.yml}），但配置文件只能约束
 * 「没有被更高优先级配置覆盖」的情况 —— 环境变量、命令行参数、
 * {@code SPRING_APPLICATION_JSON} 都能覆盖它。本类读取的是<b>最终生效值</b>，
 * 所以覆盖也逃不掉。</p>
 */
@Slf4j
@Component
public class ProdConfigGuard {

    /** 源码中公开的默认密钥：生产出现即视为配置错误。 */
    private static final List<String> LEAKED_DEFAULTS =
            List.of("geek-mall-jwt-secret-key-please-change-in-production-2026");

    private static final String PROD = "prod";

    private final Environment environment;

    @Value("${mall.jwt.secret:}")
    private String jwtSecret;

    @Value("${mall.init-demo-user:false}")
    private boolean initDemoUser;

    @Value("${mall.init-demo-merchant:false}")
    private boolean initDemoMerchant;

    @Value("${mall.mock.enabled:false}")
    private boolean mockEnabled;

    @Value("${mall.sms.expose-code:false}")
    private boolean smsExposeCode;

    @Value("${mall.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${springdoc.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    public ProdConfigGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!isProdProfile()) {
            return;
        }
        StringBuilder violations = new StringBuilder();

        if (jwtSecret == null || jwtSecret.isBlank()) {
            violations.append("\n  · mall.jwt.secret 未设置：请注入环境变量 MALL_JWT_SECRET（≥32 字节随机值）");
        } else {
            for (String leaked : LEAKED_DEFAULTS) {
                if (leaked.equals(jwtSecret)) {
                    violations.append("\n  · mall.jwt.secret 仍在使用源码中的公开默认值，任何读过仓库的人都能伪造令牌");
                }
            }
        }
        if (initDemoUser) {
            violations.append("\n  · mall.init-demo-user=true：生产会创建口令为 123456 的演示买家账号");
        }
        if (initDemoMerchant) {
            violations.append("\n  · mall.init-demo-merchant=true：生产会创建口令为 123456 的演示商家账号");
        }
        if (mockEnabled) {
            violations.append("\n  · mall.mock.enabled=true：生产会注册「模拟发货」等联调接口，可绕过真实履约");
        }
        if (smsExposeCode) {
            violations.append("\n  · mall.sms.expose-code=true：验证码会随接口响应回显，等于没有验证码");
        }
        if (!rateLimitEnabled) {
            violations.append("\n  · mall.rate-limit.enabled=false：生产关闭限流等于对撞库/刷接口敞开大门");
        }
        if (!apiDocsEnabled) {
            // 不是错误，但值得提示：关掉更安全，只是需要知道自己在关什么
            log.info("[配置自检] 生产已关闭 Swagger（springdoc.api-docs.enabled=false），接口文档不再对外暴露");
        }

        if (violations.length() > 0) {
            throw new IllegalStateException(
                    "生产环境配置自检未通过，拒绝启动：" + violations + "\n" + "  修复方式：通过环境变量 / 配置中心提供正确值后重新启动。");
        }
        log.info("[配置自检] 生产环境关键开关检查通过（演示数据已关、mock 已关、验证码不回显、限流已开）");
    }

    private boolean isProdProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains(PROD);
    }
}
