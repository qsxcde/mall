package com.geekmall.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.constant.RedisKeys;
import com.geekmall.common.constant.SecurityConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基类：真实 MySQL + Redis（Testcontainers），真实 Flyway 迁移，真实过滤器链。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>容器为进程级单例</b>（static 块手动 start，不用 {@code @Testcontainers}）。
 *       否则每个测试类都会重启一次容器，MySQL 冷启动动辄十几秒，测试会慢到没人愿意跑。
 *       容器由 Testcontainers 的 Ryuk 在 JVM 退出时回收。</li>
 *   <li><b>不用 H2</b>：迁移脚本与商家域聚合查询大量使用 MySQL 专有语法
 *       （JSON_EXTRACT / JSON_VALID、DATE_SUB(CURDATE(), INTERVAL …)、INSERT IGNORE、
 *       ON DUPLICATE KEY UPDATE），H2 兼容模式跑不通，用 H2 等于测了个假环境。</li>
 *   <li>通过 {@link DynamicPropertySource} 显式注入连接信息，不依赖 Boot 版本对
 *       {@code @ServiceConnection} 的支持细节，行为更可控。</li>
 * </ul>
 *
 * <p>数据隔离：所有测试类共用同一个库，因此写数据的测试必须自己构造独立数据
 * （唯一手机号 / 新建商品），不依赖其它测试留下的状态。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL;
    protected static final GenericContainer<?> REDIS;

    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("geek_mall")
                .withUsername("mall")
                .withPassword("mall123456")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        MYSQL.start();

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        REDIS.start();

        // 容器生命周期与 JVM 对齐：退出时主动释放，避免残留容器长期占用端口与内存
        // （Testcontainers 的 Ryuk 也会兜底回收，这里只是让释放更及时）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            REDIS.stop();
            MYSQL.stop();
        }));
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 2);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    /** 买家演示账号（由 DemoDataInitializer 在测试 profile 下创建）。 */
    protected static final String DEMO_BUYER = "13800000000";
    protected static final String DEMO_PASSWORD = "123456";

    /** 商家演示账号（由 MerchantDataInitializer 创建，店铺 id=1）。 */
    protected static final String DEMO_MERCHANT = "merchant";
    protected static final long DEMO_SHOP_ID = 1L;

    /* ------------------------------ 请求辅助 ------------------------------ */

    protected JsonNode get(String url, String token) {
        return exchange(MockMvcRequestBuilders.get(url), null, token);
    }

    protected JsonNode post(String url, Object body, String token) {
        return exchange(MockMvcRequestBuilders.post(url), body, token);
    }

    protected JsonNode put(String url, Object body, String token) {
        return exchange(MockMvcRequestBuilders.put(url), body, token);
    }

    protected JsonNode delete(String url, String token) {
        return exchange(MockMvcRequestBuilders.delete(url), null, token);
    }

    /**
     * 执行请求并解析统一响应体。
     *
     * <p>本项目约定 HTTP 状态恒为 200（业务结果在 body 的 code 里），
     * 唯有限流会返回 429，因此这里断言 200 即可暴露非预期状态码。</p>
     */
    protected JsonNode exchange(MockHttpServletRequestBuilder builder, Object body, String token) {
        try {
            builder.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (token != null) {
                builder.header(SecurityConstants.TOKEN_HEADER, SecurityConstants.TOKEN_PREFIX + token);
            }
            if (body != null) {
                builder.content(objectMapper.writeValueAsBytes(body));
            }
            String json = mockMvc.perform(builder)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("请求执行失败：" + builder, e);
        }
    }

    /** 断言业务码并返回 data 节点。 */
    protected JsonNode assertCode(JsonNode response, int code) {
        assertThat(response.get("code").asInt())
                .as("响应体：%s", response)
                .isEqualTo(code);
        return response.get("data");
    }

    /** 断言业务成功（code=0）并返回 data 节点。 */
    protected JsonNode assertSuccess(JsonNode response) {
        return assertCode(response, 0);
    }

    /* ------------------------------ 账号辅助 ------------------------------ */

    /** 买家密码登录，返回令牌。 */
    protected String loginBuyer(String account, String password) {
        JsonNode data = assertSuccess(post("/api/v1/auth/login",
                Map.of("account", account, "password", password), null));
        return data.get("token").asText();
    }

    /** 买家演示账号登录。 */
    protected String loginDemoBuyer() {
        return loginBuyer(DEMO_BUYER, DEMO_PASSWORD);
    }

    /** 商家登录，返回商家令牌。 */
    protected String loginMerchant(String account, String password) {
        JsonNode data = assertSuccess(post("/api/v1/merchant/auth/login",
                Map.of("account", account, "password", password), null));
        return data.get("token").asText();
    }

    /**
     * 走完整「发验证码 → 注册 → 登录」链路，返回新账号令牌。
     *
     * <p>测试数据隔离的关键：每次生成唯一手机号，避免与其它测试或演示数据冲突。</p>
     */
    protected String registerAndLogin(String phone) {
        return registerAndLogin(phone, DEMO_PASSWORD);
    }

    protected String registerAndLogin(String phone, String password) {
        JsonNode sms = assertSuccess(post("/api/v1/auth/sms-code",
                Map.of("phone", phone, "scene", "register"), null));
        // 回显字段名为 devCode（仅测试/开发环境返回，生产为 null）
        String code = sms.get("devCode").asText();

        assertSuccess(post("/api/v1/auth/register", Map.of(
                "phone", phone,
                "smsCode", code,
                "password", password,
                "nickname", "集成测试用户",
                "agreed", true), null));

        return loginBuyer(phone, password);
    }

    /** 随机生成一个合法的中国手机号，用于测试账号隔离。 */
    protected String randomPhone() {
        return "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    /**
     * 直接向 Redis 写入一个验证码，绕开发送接口。
     *
     * <p>发送接口有 60 秒/手机号的冷却限制，而部分用例需要在同一手机号上
     * 连续校验两次验证码（例如「注册两次」验证重复注册），走发送接口会被冷却拦下。</p>
     */
    protected void seedSmsCode(String scene, String phone, String code) {
        stringRedisTemplate.opsForValue()
                .set(RedisKeys.smsCode(scene, phone), code, java.time.Duration.ofMinutes(5));
    }
}
