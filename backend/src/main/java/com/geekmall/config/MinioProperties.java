package com.geekmall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * MinIO 对象存储配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.minio")
public class MinioProperties {

    /** 是否启用对象存储；关闭时上传接口会返回明确提示 */
    private boolean enabled = true;

    /** 服务地址，如 http://localhost:9000 */
    private String endpoint = "http://localhost:9000";

    private String accessKey = "minioadmin";

    private String secretKey = "minioadmin";

    /** 存储桶，不存在会自动创建 */
    private String bucket = "geek-mall";

    /**
     * 对外访问前缀。若通过 Nginx 反代或 CDN 暴露对象存储，
     * 这里填对外域名，生成的文件 URL 会用它拼接。
     */
    private String publicBaseUrl = "";

    /**
     * 熔断降级时允许改存本地磁盘的业务类型。
     *
     * <p>头像 / 评价图属于「展示型」附件，暂时落到本地磁盘不影响主流程；
     * 售后凭证涉及纠纷举证，必须落在可靠的对象存储上 —— 失败就应当明确报错让用户重试，
     * 而不是静默写进一个多实例部署下并不共享的本地目录里。</p>
     */
    private Set<String> degradableBiz = Set.of("avatar", "review", "common");

    /* ------------------------------------------------------------------
     * 超时配置：没有超时，熔断就永远等不到「失败」
     *
     * OkHttp 的默认超时长达 10s（连接）/ 10s（读），且不限制单次调用总时长。
     * 对象存储网络异常时，请求会长时间挂住 Tomcat 线程（max=120），
     * 120 个卡住的上传请求就足以让全站接口排队 —— 与文件无关的下单、详情一并遭殃。
     * 因此这里必须显式收紧，让「慢」尽快变成「失败」，熔断器才有机会介入。
     * ------------------------------------------------------------------ */

    /** 建立连接超时：连不上要快速失败。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 写超时：上传 10MB 需要时间，不能设得太短。 */
    private Duration writeTimeout = Duration.ofSeconds(30);

    /** 读超时。 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 单次调用总超时。
     *
     * <p>这条最关键：{@code writeTimeout} 只在「写数据长时间无进展」时触发，
     * 而「连接已建立、服务端接了请求但不返回」这种最危险的情况不会触发它，
     * 只能靠 {@code callTimeout} 兜底。</p>
     */
    private Duration callTimeout = Duration.ofSeconds(60);
}
