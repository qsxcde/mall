package com.geekmall.common.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务级熔断降级配置（{@code mall.resilience.*}）。
 *
 * <p>接入方式：{@link ResilienceGuard#execute} 的第一个参数就是资源名。
 * 未在 {@link #resources} 中单独声明的资源使用 {@link #defaults}。</p>
 *
 * <p><b>资源级配置是整体覆盖，不继承 defaults</b> —— 「部分字段继承、部分字段覆盖」
 * 会让最终生效值变得难以推演（尤其是阈值为 0 这类合法取值无法与「未配置」区分）。
 * 需要定制就把该资源的参数写全。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.resilience")
public class ResilienceProperties {

    /** 总开关。关闭后 {@link ResilienceGuard} 直接执行原逻辑，不做熔断与降级。 */
    private boolean enabled = true;

    /** 兜底参数。 */
    private Resource defaults = new Resource();

    /** 按资源名定制的参数。 */
    private Map<String, Resource> resources = new LinkedHashMap<>();

    /** 取指定资源生效的配置。 */
    public Resource forResource(String resource) {
        Resource specific = resources.get(resource);
        return specific == null ? defaults : specific;
    }

    /**
     * 单个资源的熔断参数。
     *
     * <p>默认值对齐 Resilience4j 的常见配置，便于横向理解。</p>
     */
    @Data
    public static class Resource {

        /** 该资源是否启用熔断。 */
        private boolean enabled = true;

        /**
         * 滑动窗口内至少要有这么多次调用，才会评估是否熔断。
         *
         * <p>存在的意义：低流量场景下「2 次调用 1 次失败」= 50% 失败率，
         * 若不设最小样本数，半夜的一个偶发异常就能把依赖熔断掉。</p>
         */
        private int minimumCalls = 10;

        /** 失败率阈值（百分比），达到即熔断。 */
        private float failureRateThreshold = 50f;

        /** 超过该时长视为慢调用。 */
        private Duration slowCallDuration = Duration.ofSeconds(3);

        /**
         * 慢调用率阈值（百分比），达到即熔断；{@code <= 0} 表示不启用慢调用判定。
         *
         * <p>这条对「下游没报错，但就是很慢」的场景最关键 ——
         * 慢调用占满线程池造成的后果，比直接报错更严重。</p>
         */
        private float slowCallRateThreshold = 80f;

        /** 滑动窗口时长。 */
        private Duration slidingWindow = Duration.ofSeconds(60);

        /** 熔断打开后多久进入半开态（放探测请求试恢复）。 */
        private Duration waitDurationInOpen = Duration.ofSeconds(10);

        /** 半开态最多放行的探测请求数。 */
        private int permittedCallsInHalfOpen = 3;

        /**
         * 并发上限（信号量）；{@code <= 0} 表示不限制。
         *
         * <p>熔断是<b>事后</b>的：要等失败累计到阈值才打开，这期间线程已经被占住了。
         * 所以对慢依赖应先用并发上限把请求挡在门外，熔断器只作为兜底。</p>
         */
        private int maxConcurrentCalls = 0;
    }
}
