package com.geekmall.modules.content.support;

import com.geekmall.common.exception.BizException;
import com.geekmall.common.result.ResultCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件的公共校验与对象名生成。
 *
 * <p>抽出来是因为 MinIO 与本地磁盘两种实现必须用<b>同一套</b>安全规则，
 * 否则切换存储方式时会悄悄放松校验。</p>
 *
 * <p><b>不信任客户端 Content-Type</b>：先做 MIME 白名单粗筛，再读取文件头魔数，
 * 以实际内容决定最终类型。只改 Content-Type 伪装成图片的 HTML/脚本会被拒绝。</p>
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

    /** 单张图片最大像素数（约 5000×5000），防止解压炸弹耗尽内存。 */
    private static final long MAX_PIXELS = 25_000_000L;

    private static final int MAGIC_LENGTH = 12;

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private FileValidator() {}

    /**
     * 校验并返回<b>按文件内容识别</b>的图片类型。
     *
     * <p>MIME 白名单只是第一层粗筛；最终类型以文件头魔数为准，避免
     * 「Content-Type: image/png + HTML 内容」绕过校验。</p>
     */
    public static String validateAndDetect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "上传文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(ResultCode.PARAM_ERROR, "图片不能超过 10MB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentTypeOf(file))) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅支持 jpg / png / webp / gif 图片");
        }
        String detected = detectByMagic(file);
        if (detected == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅支持 jpg / png / webp / gif 图片");
        }
        validateDimensions(file, detected);
        return detected;
    }

    /** 兼容旧调用：只做校验，不关心识别结果。 */
    public static void validate(MultipartFile file) {
        validateAndDetect(file);
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

    private static String detectByMagic(MultipartFile file) {
        byte[] header;
        try (InputStream inputStream = file.getInputStream()) {
            header = inputStream.readNBytes(MAGIC_LENGTH);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "读取上传文件失败");
        }
        if (header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 'P'
                && header[2] == 'N'
                && header[3] == 'G'
                && (header[4] & 0xFF) == 0x0D
                && (header[5] & 0xFF) == 0x0A
                && (header[6] & 0xFF) == 0x1A
                && (header[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        if (header.length >= 6 && (startsWith(header, 0, "GIF87a") || startsWith(header, 0, "GIF89a"))) {
            return "image/gif";
        }
        if (header.length >= 12 && startsWith(header, 0, "RIFF") && startsWith(header, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    /** 读取图片尺寸，拒绝像素数超过上限的图片（防解压炸弹）。WEBP 不支持，跳过。 */
    private static void validateDimensions(MultipartFile file, String detected) {
        if ("image/webp".equals(detected)) {
            return;
        }
        try (InputStream inputStream = file.getInputStream();
                ImageInputStream imageInput = ImageIO.createImageInputStream(inputStream)) {
            if (imageInput == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "图片格式损坏");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new BizException(ResultCode.PARAM_ERROR, "图片格式损坏");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new BizException(ResultCode.PARAM_ERROR, "图片尺寸过大，请压缩后再上传");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "图片格式损坏");
        }
    }

    private static boolean startsWith(byte[] data, int offset, String ascii) {
        if (data.length < offset + ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (data[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
