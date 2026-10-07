package com.geekmall.modules.content.service.impl;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.content.service.FileService;
import com.geekmall.modules.content.support.FileValidator;
import com.geekmall.modules.content.vo.UploadResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储实现（{@code mall.storage.type=local}）。
 *
 * <p>存在的意义：本地开发 / 单机部署时不必强依赖 MinIO。文件写入
 * {@code mall.storage.local-dir}，并通过 {@code /uploads/**} 静态资源暴露
 * （见 {@code LocalStorageConfig}）。</p>
 *
 * <p>生产环境请使用 minio 实现：多实例部署下本地磁盘不共享，且不利于扩容与备份。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "local")
public class LocalFileServiceImpl implements FileService {

    private final String localDir;
    private final String publicBaseUrl;

    public LocalFileServiceImpl(@Value("${mall.storage.local-dir:./uploads}") String localDir,
                                @Value("${mall.storage.local-public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.localDir = localDir;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public UploadResultVO upload(MultipartFile file, String biz) {
        FileValidator.validate(file);
        String contentType = FileValidator.contentTypeOf(file);
        String relativePath = FileValidator.buildObjectName(biz, contentType);

        Path basePath = Paths.get(localDir).toAbsolutePath().normalize();
        Path target = basePath.resolve(relativePath).normalize();
        // 双保险：即使相对路径被污染也不允许写出根目录
        if (!target.startsWith(basePath)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的上传路径");
        }
        try {
            Files.createDirectories(target.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("本地文件写入失败：{}", target, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "文件保存失败，请稍后重试");
        }
        log.info("文件已写入本地：{}（{} bytes）", target, file.getSize());
        return new UploadResultVO(publicUrl(relativePath), relativePath, file.getSize(), contentType);
    }

    private String publicUrl(String relativePath) {
        String base = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/uploads/" + relativePath;
    }
}
