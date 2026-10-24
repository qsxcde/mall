package com.geekmall.config;

import com.geekmall.common.constant.SecurityConstants;
import com.geekmall.security.JwtAuthenticationFilter;
import com.geekmall.security.MerchantJwtAuthenticationFilter;
import com.geekmall.security.RestAccessDeniedHandler;
import com.geekmall.security.RestAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Security 配置：无状态 JWT 鉴权。
 *
 * <p>白名单内的接口匿名可访问，其余接口均需携带有效令牌，与前端 {@code requiresAuth} 路由对应。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MerchantJwtAuthenticationFilter merchantJwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;

    /** 观测抓取专用账号的权限标识。 */
    private static final String OBS_ROLE = "OBS";

    /**
     * Actuator 专用过滤链：健康检查匿名可达（探针不带凭证），其余管理端点
     * （info / metrics / prometheus）必须持「观测账号」的 HTTP Basic 凭证。
     *
     * <p>指标里含业务量与内部结构信息（QPS、缓存命中、队列积压等），
     * 不能匿名抓取；与主链分离后，业务令牌与观测凭证互不影响。
     * Prometheus 的 scrape 配置携带 basic_auth 即可继续工作。</p>
     *
     * <p>鉴权失败走 HTTP Basic 默认语义（401 + {@code WWW-Authenticate}），
     * 而不是业务 API 的「200 + code 401」约定 —— Prometheus 等抓取端只认 HTTP 状态码。</p>
     */
    @Bean
    @Order(0)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .hasRole(OBS_ROLE))
                .httpBasic(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * 观测抓取专用账号（内存态）。密码来自 {@code mall.observability.scrape-password}
     * （环境变量 {@code MALL_OBS_PASSWORD}），生产无兜底默认值，缺失即启动失败。
     */
    @Bean
    public InMemoryUserDetailsManager obsUserDetailsManager(
            @Value("${mall.observability.scrape-password:}") String scrapePassword) {
        if (!StringUtils.hasText(scrapePassword)) {
            throw new IllegalStateException(
                    "mall.observability.scrape-password 未设置：请注入环境变量 MALL_OBS_PASSWORD（观测端点抓取凭证）");
        }
        return new InMemoryUserDetailsManager(User.withUsername("obs")
                .password(passwordEncoder().encode(scrapePassword))
                .roles(OBS_ROLE)
                .build());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        // 异步分发（DeferredResult）会再次进入过滤链，此时 ThreadLocal 中的登录态已随原请求清理，
                        // 若不放行 ASYNC 会被 AuthorizationFilter 误判为未登录返回 401。
                        // 真正的鉴权已在最初的 REQUEST 分发完成。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers(SecurityConstants.WHITELIST)
                        .permitAll()
                        // 商家端：白名单里的 /api/v1/merchant/auth/** 已放行，其余 /api/v1/merchant/** 必须持有
                        // ROLE_MERCHANT（只有 scope=merchant 的令牌会被 MerchantJwtAuthenticationFilter 授予）。
                        // 这是一道**声明式**闸门：不再依赖「每个商家 service 是否记得调
                        // MerchantSecurityUtils.getShopId()」，买家令牌在此直接 403，而不是进到 service 才抛 401。
                        .requestMatchers("/api/v1/merchant/**")
                        .hasAuthority(SecurityConstants.ROLE_MERCHANT)
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(handler -> handler.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 商家端令牌（scope=merchant）在买家侧过滤器之后解析，两端互不干扰
                .addFilterAfter(merchantJwtAuthenticationFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 阻止 JwtAuthenticationFilter 被 Servlet 容器重复自动注册（它只在 Security 过滤链中生效）。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
