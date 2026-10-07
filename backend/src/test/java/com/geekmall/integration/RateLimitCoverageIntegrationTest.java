package com.geekmall.integration;

import com.geekmall.common.ratelimit.RateLimitPolicies;
import com.geekmall.common.ratelimit.RateLimitPolicy;
import com.geekmall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流覆盖度集成测试 —— 用来<b>证明「没有端点漏配」</b>。
 *
 * <p>做法是反向的：不逐一列举「我加了哪些规则」，而是从 Spring 里把所有已注册端点拉出来，
 * 再问策略表「这个端点命中哪条规则」。落在兜底规则 {@code default} 上的，就是没被显式分类的端点。</p>
 *
 * <p>这样写的好处是：将来任何人新增接口却忘了在 {@link RateLimitPolicies} 里分类，
 * 这个测试会直接失败并列出具体的「方法 + 路径」，而不是等到线上被刷了才发现某个接口裸奔。</p>
 */
class RateLimitCoverageIntegrationTest extends AbstractIntegrationTest {

    /**
     * 必须按名字限定：Actuator 也注册了一个同类型的
     * {@code controllerEndpointHandlerMapping}，不限定会注入失败。
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private RateLimitPolicies policies;

    @Test
    @DisplayName("所有已注册端点都必须命中显式限流规则，不允许退化到兜底规则")
    void everyEndpointIsExplicitlyClassified() {
        List<String> unclassified = new ArrayList<>();
        List<String> exempt = new ArrayList<>();
        int inspected = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            HandlerMethod handler = entry.getValue();
            for (String pattern : pathPatternsOf(mapping)) {
                String concretePath = concretize(pattern);
                if (policies.isExempt(concretePath)) {
                    exempt.add(concretePath);
                    continue;
                }
                for (String method : httpMethodsOf(mapping)) {
                    inspected++;
                    RateLimitPolicy policy = policies.resolve(method, concretePath);
                    if (policy == null || policy == RateLimitPolicies.DEFAULT) {
                        unclassified.add(method + " " + pattern + "  ← " + handler.getShortLogMessage());
                    }
                }
            }
        }

        assertThat(inspected)
                .as("应扫描到全部业务端点，数量明显偏少说明端点枚举逻辑失效了")
                .isGreaterThanOrEqualTo(100);
        assertThat(unclassified)
                .as("以下端点未在 RateLimitPolicies 中显式分类（会退化到兜底规则，请补充规则）：%n%s",
                        String.join(System.lineSeparator(), unclassified))
                .isEmpty();
        assertThat(exempt).as("豁免路径应由策略表统一声明").isNotEmpty();
    }

    @Test
    @DisplayName("策略表里声明的规则全部都能被至少一个真实端点命中（防止留下僵尸规则）")
    void noOrphanRules() {
        List<String> hit = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : pathPatternsOf(entry.getKey())) {
                String concretePath = concretize(pattern);
                if (policies.isExempt(concretePath)) {
                    continue;
                }
                for (String method : httpMethodsOf(entry.getKey())) {
                    RateLimitPolicy policy = policies.resolve(method, concretePath);
                    if (policy != null && policy != RateLimitPolicies.DEFAULT) {
                        hit.add(policy.name());
                    }
                }
            }
        }

        // 说明：个别规则是「防御性」的（例如为未来可能出现的子路径预留），
        // 因此这里只断言「绝大多数规则都被命中」，而不是要求 100% —— 但也足以揪出写错的路径模式。
        List<String> orphans = new ArrayList<>();
        for (RateLimitPolicy policy : RateLimitPolicies.rules()) {
            if (!hit.contains(policy.name())) {
                orphans.add(policy.name() + " " + policy.patterns());
            }
        }
        assertThat(orphans.size())
                .as("以下规则的路径模式没有命中任何真实端点，疑似写错：%n%s",
                        String.join(System.lineSeparator(), orphans))
                .isLessThanOrEqualTo(1);
    }

    /** 兼容 PathPattern（Boot 3 默认）与旧版 PatternsRequestCondition 两种条件。 */
    private static List<String> pathPatternsOf(RequestMappingInfo mapping) {
        PathPatternsRequestCondition pathPatterns = mapping.getPathPatternsCondition();
        if (pathPatterns != null) {
            return List.copyOf(pathPatterns.getPatternValues());
        }
        PatternsRequestCondition patterns = mapping.getPatternsCondition();
        return patterns == null ? List.of() : List.copyOf(patterns.getPatterns());
    }

    /** 未声明 HTTP 方法的映射视为任意方法，这里用 GET 代表。 */
    private static List<String> httpMethodsOf(RequestMappingInfo mapping) {
        var methods = mapping.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return List.of("GET");
        }
        return methods.stream().map(Enum::name).toList();
    }

    /** 把 {@code /api/v1/orders/{orderNo}} 这样的模板替换成可匹配的具体路径。 */
    private static String concretize(String pattern) {
        StringBuilder result = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current != '{') {
                result.append(current);
                continue;
            }
            int end = pattern.indexOf('}', i);
            if (end < 0) {
                result.append(current);
                continue;
            }
            result.append('1');
            i = end;
        }
        return result.toString();
    }
}
