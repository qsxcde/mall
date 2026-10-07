package com.geekmall.config;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置。
 *
 * <p>注意：这里只创建客户端，<b>不在启动时探测连接或建桶</b>——
 * 否则对象存储不可用会导致整个应用启动失败。建桶推迟到首次上传时进行。</p>
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioConfig {

    private final MinioProperties properties;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
