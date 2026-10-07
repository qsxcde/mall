package com.geekmall.config;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置。
 *
 * <p>注意两点：</p>
 * <ol>
 *   <li><b>不在启动时探测连接或建桶</b> —— 否则对象存储不可用会导致整个应用启动失败。
 *       建桶推迟到首次上传时进行。</li>
 *   <li><b>必须显式设置超时</b> —— OkHttp 默认超时较长且不限制单次调用总时长，
 *       对象存储网络异常时上传请求会长期挂住 Tomcat 线程（max=120），
 *       进而把与文件无关的接口一起拖垮。超时也是业务级熔断能生效的前提：
 *       熔断器要等到调用「失败」才能计数，而「慢」只有在配置了超时之后才会变成「失败」。</li>
 * </ol>
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioConfig {

    private final MinioProperties properties;

    @Bean
    public MinioClient minioClient() {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout())
                .writeTimeout(properties.getWriteTimeout())
                .readTimeout(properties.getReadTimeout())
                .callTimeout(properties.getCallTimeout())
                .build();
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .httpClient(httpClient)
                .build();
    }
}
