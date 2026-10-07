package com.geekmall.common.ratelimit;

import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

/**
 * 一条限流规则：把「哪些请求」映射到「什么限制」。
 *
 * @param name          规则名，同时作为 Redis key 的一部分与日志标识；全局唯一
 * @param methods       生效的 HTTP 方法；{@code null} 或空集表示不限方法
 * @param patterns      Ant 风格路径模式（{@code *} 单段、{@code **} 多段）
 * @param dimension     限流维度
 * @param limit         窗口内允许的请求数
 * @param windowSeconds 窗口大小（秒）
 * @param maxConcurrent 单实例最大并发在途请求数；{@code 0} 表示不限制
 * @param tier          执行层级（进程内 / Redis）
 * @param fallback      组件故障时的降级策略
 */
public record RateLimitPolicy(String name,
                              Set<HttpMethod> methods,
                              List<String> patterns,
                              RateLimitDimension dimension,
                              int limit,
                              int windowSeconds,
                              int maxConcurrent,
                              RateLimitTier tier,
                              RateLimitFallback fallback) {

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("限流规则名不能为空");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("限流阈值必须为正数：" + name);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("限流窗口必须为正数：" + name);
        }
        if (patterns == null || patterns.isEmpty()) {
            throw new IllegalArgumentException("限流规则必须至少有一个路径模式：" + name);
        }
    }

    /** 是否对所有 HTTP 方法生效。 */
    public boolean anyMethod() {
        return methods == null || methods.isEmpty();
    }

    /** 该方法是否命中本规则。 */
    public boolean supports(String httpMethod) {
        if (anyMethod()) {
            return true;
        }
        return methods.stream().anyMatch(m -> m.matches(httpMethod));
    }

    /** 供文档与测试使用的单行描述。 */
    public String describe() {
        String methodText = anyMethod() ? "ALL" : String.join("/", methods.stream().map(HttpMethod::name).toList());
        String concurrent = maxConcurrent > 0 ? "，并发 " + maxConcurrent : "";
        return methodText + " " + String.join(", ", patterns)
                + " → " + dimension + " 维度，"
                + limit + " 次 / " + windowSeconds + "s"
                + concurrent + "，"
                + tier + "，故障时 " + fallback;
    }
}
