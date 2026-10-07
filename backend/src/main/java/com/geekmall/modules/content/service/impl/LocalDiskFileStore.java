package com.geekmall.modules.content.service.impl;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import com.geekmall.modules.content.support.FileValidator;
import com.geekmall.modules.content.vo.UploadResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘写入组件。
 *
 * <p>为什么单独抽一个组件，而不是直接写在 {@link LocalFileServiceImpl} 里：</p>
 * <ul>
 *   <li>它要作为<b>对象存储熔断后的降级目标</b>。而 {@code LocalFileServiceImpl} 只在
 *       {@code mall.storage.type=local} 时才注册成 bean —— 用 MinIO 的环境里根本没有它，
 *       也就无从降级。本类始终注册，因此随时可用。</li>
 *   <li>本地写入的安全规则（{@link FileValidator} 的路径校验、目录逃逸防护）只能有一份，
 *       否则「降级路径」会比「主路径」放松校验。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalDiskFileStore {

    private final String localDir;
    private final String publicBaseUrl;

    public LocalDiskFileStore(@Value("${mall.storage.local-dir:./uploads}") String localDir,
                              @Value("${mall.storage.local-public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.localDir = localDir;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** 完整流程：校验 → 生成对象名 → 落盘。供 local 存储实现使用。 */
    public UploadResultVO store(MultipartFile file, String biz) {
        FileValidator.validate(file);
        String contentType = FileValidator.contentTypeOf(file);
        String relativePath = FileValidator.buildObjectName(biz, contentType);
        return storeValidated(file, contentType, relativePath, false);
    }

    /**
     * 写入已校验的文件。
     *
     * <p>降级路径复用它，从而可以沿用主路径已经生成好的对象名，
     * 避免「同一份文件在两次尝试里名字不同」造成的排查困难。</p>
     *
     * @param degraded 是否为降级写入（会打 WARN 日志并在响应里标记）
     */
    public UploadResultVO storeValidated(MultipartFile file, String contentType,
                                         String relativePath, boolean degraded) {
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

        UploadResultVO vo = UploadResultVO.builder()
                .url(publicUrl(relativePath))
                .objectName(relativePath)
                .size(file.getSize())
                .contentType(contentType)
                .degraded(degraded ? Boolean.TRUE : null)
                .build();

        if (degraded) {
            log.warn("[降级] 对象存储不可用，文件已改存本地磁盘：{}"
                    + "（多实例部署时该文件对其它节点不可见，需人工核对是否需要补传）", relativePath);
        } else {
            log.info("文件已写入本地：{}（{} bytes）", target, file.getSize());
        }
        return vo;
    }

    private String publicUrl(String relativePath) {
        String base = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/uploads/" + relativePath;
    }
}
