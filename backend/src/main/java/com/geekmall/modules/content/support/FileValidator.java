package com.geekmall.modules.content.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 上传文件的公共校验与对象名生成。
 *
 * <p>抽出来是因为 MinIO 与本地磁盘两种实现必须用<b>同一套</b>安全规则，
 * 否则切换存储方式时会悄悄放松校验。</p>
 */
public final class FileValidator {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");

    /** 按 content-type 推断扩展名，避免信任原始文件名（防路径穿越 / 伪装扩展名） */
    private static final Map<String, String> EXT_BY_TYPE = Map.of(
            "image/jpeg", ".jpg",
            "image/jpg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif");

    private static final Set<String> ALLOWED_BIZ = Set.of("avatar", "review", "aftersale", "common");

    private static final long MAX_SIZE = 10 * 1024 * 1024L;

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private FileValidator() {
    }

    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "上传文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(ResultCode.PARAM_ERROR, "图片不能超过 10MB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentTypeOf(file))) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅支持 jpg / png / webp / gif 图片");
        }
    }

    public static String contentTypeOf(MultipartFile file) {
        return file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    }

    /** 相对路径：{biz}/{yyyy}/{MM}/{dd}/{uuid}{ext}，天然按天归档且不会重名。 */
    public static String buildObjectName(String biz, String contentType) {
        String safeBiz = (StringUtils.hasText(biz) && ALLOWED_BIZ.contains(biz)) ? biz : "common";
        // EXT_BY_TYPE 是不可变 Map，不允许 null key，因此先归一化再查表
        String ext = EXT_BY_TYPE.getOrDefault(contentType == null ? "" : contentType, ".png");
        return safeBiz + "/" + LocalDate.now().format(DATE_PATH) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;
    }
}
