package com.geekmall.modules.content.service.impl;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.resilience.ResilienceGuard;
import com.geekmall.common.resilience.ResilienceMetrics;
import com.geekmall.common.resilience.ResilienceProperties;
import com.geekmall.common.result.ResultCode;
import com.geekmall.config.MinioProperties;
import com.geekmall.modules.content.vo.UploadResultVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对象存储上传的熔断降级测试。
 *
 * <p>本测试盯住的是<b>「按业务语义决定能不能降级」这条策略</b>，
 * 而不是熔断器本身的行为（那由 {@code CircuitBreakerTest} 覆盖）：</p>
 * <ul>
 *   <li>展示型附件（头像）在对象存储不可用时，应改存本地磁盘并在响应里标记；</li>
 *   <li>售后凭证<b>不允许降级</b>，必须明确失败让用户重试 —— 静默落到本地磁盘
 *       会让凭证在多实例部署下丢失，而它是纠纷举证的依据；</li>
 *   <li>参数非法属于业务前置校验，必须在熔断保护之外拦下，
 *       既不能算作「依赖失败」，也不能触发降级。</li>
 * </ul>
 */
@DisplayName("MinioFileServiceImpl 上传熔断降级")
class MinioFileServiceImplTest {

    @TempDir
    Path tempDir;

    private MinioClient minioClient;
    private MinioFileServiceImpl service;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        MinioProperties properties = new MinioProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://localhost:9000");

        ResilienceProperties resilienceProperties = new ResilienceProperties();
        ResilienceProperties.Resource config = new ResilienceProperties.Resource();
        config.setMinimumCalls(2);
        config.setFailureRateThreshold(50f);
        config.setSlowCallRateThreshold(0f);
        config.setSlidingWindow(Duration.ofSeconds(30));
        config.setWaitDurationInOpen(Duration.ofSeconds(30));
        resilienceProperties.setDefaults(config);

        ResilienceGuard guard = new ResilienceGuard(resilienceProperties,
                new ResilienceMetrics(new SimpleMeterRegistry()));
        LocalDiskFileStore localDiskFileStore =
                new LocalDiskFileStore(tempDir.toString(), "http://localhost:8080");

        service = new MinioFileServiceImpl(minioClient, properties, guard, localDiskFileStore);
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "avatar.png", "image/png", "fake-image-bytes".getBytes());
    }

    /** 让对象存储「不可用」：建桶探测即抛异常。 */
    private void minioUnavailable() throws Exception {
        when(minioClient.bucketExists(any())).thenThrow(new RuntimeException("connection refused"));
    }

    @Test
    @DisplayName("头像上传在对象存储不可用时降级到本地磁盘，并在响应里标记降级")
    void avatarShouldDegradeToLocalDisk() throws Exception {
        minioUnavailable();

        UploadResultVO result = service.upload(pngFile(), "avatar");

        assertThat(result.getDegraded())
                .as("降级必须可识别，否则用户以为文件进了对象存储")
                .isTrue();
        assertThat(result.getUrl()).startsWith("http://localhost:8080/uploads/avatar/");
        assertThat(result.getObjectName()).startsWith("avatar/");
    }

    @Test
    @DisplayName("售后凭证不允许降级：对象存储不可用时明确失败，而不是静默写本地磁盘")
    void aftersaleShouldFailInsteadOfDegrading() throws Exception {
        minioUnavailable();

        assertThatThrownBy(() -> service.upload(pngFile(), "aftersale"))
                .as("凭证涉及纠纷举证，降级到不共享的本地磁盘等于丢数据")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("参数校验在熔断保护之外：非法文件直接拒绝，不碰下游也不触发降级")
    void invalidFileShouldBeRejectedBeforeResilience() {
        MockMultipartFile exe = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", "x".getBytes());

        assertThatThrownBy(() -> service.upload(exe, "avatar"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()));
        verifyNoInteractions(minioClient);
    }
}
