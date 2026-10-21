package com.geekmall.modules.content.controller;

import com.geekmall.modules.content.support.LocalFileAccessSigner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地磁盘敏感文件访问端点。
 *
 * <p>仅接受由 {@link LocalFileAccessSigner} 生成的签名 URL；签名里包含路径与过期时间，
 * 因此不需要登录态，也能让 {@code <img>} 之类的标签直接访问。路径做了根目录逃逸校验。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class LocalFileAccessController {

    private final LocalFileAccessSigner signer;

    @Value("${mall.storage.local-dir:./uploads}")
    private String localDir;

    @GetMapping("/local")
    public ResponseEntity<Resource> local(
            @RequestParam String path, @RequestParam long expires, @RequestParam String sig) {
        if (!signer.verify(path, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Path base = Paths.get(localDir).toAbsolutePath().normalize();
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }

        String contentType;
        try {
            contentType = Files.probeContentType(target);
        } catch (Exception e) {
            contentType = null;
        }
        MediaType mediaType =
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + target.getFileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(target));
    }
}
