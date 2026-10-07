package com.geekmall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}
