package com.geekmall.modules.content.service.impl;

import com.geekmall.modules.content.service.FileService;
import com.geekmall.modules.content.support.FileValidator;
import com.geekmall.modules.content.support.LocalFileAccessSigner;
import com.geekmall.modules.content.vo.UploadResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地磁盘存储实现（{@code mall.storage.type=local}）。
 *
 * <p>存在的意义：本地开发 / 单机部署时不必强依赖 MinIO。实际写入逻辑在
 * {@link LocalDiskFileStore}（它同时也是对象存储熔断后的降级目标）。</p>
 *
 * <p>生产环境请使用 minio 实现：多实例部署下本地磁盘不共享，且不利于扩容与备份。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "local")
public class LocalFileServiceImpl implements FileService {

    private final LocalDiskFileStore localDiskFileStore;
    private final LocalFileAccessSigner localSigner;

    @Override
    public UploadResultVO upload(MultipartFile file, String biz) {
        return localDiskFileStore.store(file, biz);
    }

    @Override
    public String refreshUrl(String url) {
        String objectName = FileValidator.extractObjectName(url);
        // 敏感文件按次重新签发短时效地址；展示型附件是公开地址，原样返回
        if (objectName != null && objectName.startsWith("aftersale/")) {
            return localSigner.signedUrl(objectName);
        }
        return url;
    }
}
