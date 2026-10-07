package com.geekmall.modules.content.service.impl;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.resilience.ResilienceGuard;
import com.geekmall.common.result.ResultCode;
import com.geekmall.config.MinioProperties;
import com.geekmall.modules.content.service.FileService;
import com.geekmall.modules.content.support.FileValidator;
import com.geekmall.modules.content.vo.UploadResultVO;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 对象存储实现（MinIO / 兼容 S3 的服务）。默认实现。
 *
 * <p>对外部依赖的调用全部包在 {@link ResilienceGuard} 内，从而具备业务级熔断降级能力：
 * 对象存储变慢或不可用时，展示型附件（头像 / 评价图）自动改存本地磁盘，
 * 而售后凭证这类必须可靠落库的场景则明确报错，让用户重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioFileServiceImpl implements FileService {

    /** 熔断资源名，对应 {@code mall.resilience.resources.minio-upload}。 */
    static final String RESOURCE = "minio-upload";

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final ResilienceGuard resilienceGuard;
    private final LocalDiskFileStore localDiskFileStore;

    /** 桶初始化只做一次，避免每次上传都探测 */
    private volatile boolean bucketReady = false;

    @Override
    public UploadResultVO upload(MultipartFile file, String biz) {
        if (!properties.isEnabled()) {
            throw new BizException(ResultCode.BIZ_ERROR, "对象存储未启用（mall.minio.enabled=false）");
        }
        // 参数校验刻意放在熔断保护之外：用户传错格式不该被计成「依赖失败」，
        // 更不能因此把一个非法文件降级写进本地磁盘
        FileValidator.validate(file);
        String contentType = FileValidator.contentTypeOf(file);
        String objectName = FileValidator.buildObjectName(biz, contentType);

        ResilienceGuard.Action<UploadResultVO> primary = () -> putObject(file, objectName, contentType);
        if (properties.getDegradableBiz().contains(biz)) {
            // 展示型附件：对象存储不可用时改存本地磁盘，用户无感（响应里会带 degraded 标记）
            return resilienceGuard.execute(RESOURCE, primary,
                    () -> localDiskFileStore.storeValidated(file, contentType, objectName, true));
        }
        // 不可降级的业务（如售后凭证）：熔断打开时直接失败，让用户稍后重试，
        // 而不是静默写到一个多实例部署下并不共享的本地目录里
        return resilienceGuard.execute(RESOURCE, primary);
    }

    /* ------------------------------ 私有方法 ------------------------------ */

    /** 真正的外部调用：建桶（仅首次）+ 上传。异常统一交给 ResilienceGuard 判定。 */
    private UploadResultVO putObject(MultipartFile file, String objectName, String contentType) throws Exception {
        ensureBucket();
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            log.error("文件上传失败：{}", objectName, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "文件上传失败，请稍后重试");
        }
        log.info("文件上传成功：{}（{} bytes）", objectName, file.getSize());
        return UploadResultVO.builder()
                .url(publicUrl(objectName))
                .objectName(objectName)
                .size(file.getSize())
                .contentType(contentType)
                .build();
    }

    /**
     * 建桶 + 设置匿名只读策略。
     *
     * <p>之所以延迟到首次上传：对象存储不可用时不应该拖垮整个应用启动。</p>
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                String bucket = properties.getBucket();
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("已创建存储桶 {}", bucket);
                }
                // 允许匿名读取，前端才能直接用 URL 展示图片；生产建议改用预签名 URL 或私有桶 + CDN
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],"
                        + "\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucket).config(policy).build());
                bucketReady = true;
            } catch (Exception e) {
                log.error("初始化存储桶失败，请确认 MinIO 已启动：{}", properties.getEndpoint(), e);
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "对象存储不可用，请确认 MinIO 已启动（" + properties.getEndpoint()
                                + "），或将 mall.storage.type 切为 local 使用本地磁盘存储");
            }
        }
    }

    private String publicUrl(String objectName) {
        String base = StringUtils.hasText(properties.getPublicBaseUrl())
                ? properties.getPublicBaseUrl()
                : properties.getEndpoint();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + properties.getBucket() + "/" + objectName;
    }
}
