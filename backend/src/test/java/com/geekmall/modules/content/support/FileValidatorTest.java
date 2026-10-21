package com.geekmall.modules.content.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.geekmall.common.exception.BizException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件校验单元测试。
 *
 * <p>MinIO 与本地磁盘两种存储实现共用这一套规则，因此这里的边界即整体安全边界：
 * 大小、MIME 白名单、以及「不信任原始文件名」的扩展名推断。</p>
 */
class FileValidatorTest {

    private static MockMultipartFile image(String contentType, int size) {
        return new MockMultipartFile("file", "photo", contentType, imageBytes(contentType, size));
    }

    /** 生成真实可解码的 1×1 图片（尺寸校验需要），再按需填充到指定大小。 */
    private static byte[] imageBytes(String contentType, int size) {
        if (size == 0) {
            return new byte[0];
        }
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if ("image/webp".equals(normalized)) {
            byte[] webp = new byte[12];
            System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
            System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
            return pad(webp, size);
        }
        String format =
                switch (normalized) {
                    case "image/jpeg", "image/jpg" -> "jpg";
                    case "image/png" -> "png";
                    case "image/gif" -> "gif";
                    default -> null;
                };
        if (format == null) {
            return new byte[size];
        }
        try {
            BufferedImage buffered = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(buffered, format, output);
            return pad(output.toByteArray(), size);
        } catch (IOException e) {
            throw new IllegalStateException("构造测试图片失败", e);
        }
    }

    private static byte[] pad(byte[] source, int size) {
        return source.length >= size ? source : Arrays.copyOf(source, size);
    }

    @Nested
    @DisplayName("校验")
    class Validate {

        @Test
        @DisplayName("空文件或 null 直接拒绝")
        void rejectEmpty() {
            assertThatThrownBy(() -> FileValidator.validate(null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("上传文件为空");
            assertThatThrownBy(() -> FileValidator.validate(image("image/png", 0)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("上传文件为空");
        }

        @Test
        @DisplayName("超过 10MB 拒绝")
        void rejectOversize() {
            MultipartFile oversize = image("image/png", 10 * 1024 * 1024 + 1);
            assertThatThrownBy(() -> FileValidator.validate(oversize))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("10MB");
        }

        @Test
        @DisplayName("恰好 10MB 允许通过（边界取上界包含）")
        void allowExactlyMaxSize() {
            assertThatCode(() -> FileValidator.validate(image("image/jpeg", 10 * 1024 * 1024)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"})
        @DisplayName("白名单内的图片类型允许通过")
        void allowWhitelistedTypes(String contentType) {
            assertThatCode(() -> FileValidator.validate(image(contentType, 128)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"text/plain", "application/pdf", "application/x-sh", "image/svg+xml"})
        @DisplayName("非图片类型一律拒绝")
        void rejectNonImage(String contentType) {
            assertThatThrownBy(() -> FileValidator.validate(image(contentType, 128)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("仅支持");
        }

        @Test
        @DisplayName("MIME 缺失时拒绝，不依赖文件扩展名")
        void rejectMissingContentType() {
            assertThatThrownBy(() -> FileValidator.validate(image(null, 128))).isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("MIME 大小写不敏感")
        void contentTypeIsCaseInsensitive() {
            assertThatCode(() -> FileValidator.validate(image("IMAGE/PNG", 128)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MIME 合法但内容不是图片（魔数不符）→ 拒绝")
        void rejectSpoofedContentType() {
            MockMultipartFile spoof = new MockMultipartFile(
                    "file", "photo", "image/png", "<html>not an image</html>".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> FileValidator.validate(spoof))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("仅支持");
        }
    }

    @Nested
    @DisplayName("类型归一化")
    class ContentTypeOf {

        @Test
        @DisplayName("统一转小写，null 归一为空串")
        void shouldNormalize() {
            assertThat(FileValidator.contentTypeOf(image("IMAGE/JPEG", 1))).isEqualTo("image/jpeg");
            assertThat(FileValidator.contentTypeOf(image(null, 1))).isEmpty();
        }
    }

    @Nested
    @DisplayName("对象名生成")
    class BuildObjectName {

        @ParameterizedTest(name = "{0} → 扩展名 {1}")
        @CsvSource({"image/jpeg, .jpg", "image/jpg, .jpg", "image/png, .png", "image/webp, .webp", "image/gif, .gif"})
        @DisplayName("扩展名由 content-type 推断，不采信原始文件名")
        void extensionDerivedFromContentType(String contentType, String ext) {
            String objectName = FileValidator.buildObjectName("avatar", contentType);
            assertThat(objectName).endsWith(ext);
            assertThat(objectName).startsWith("avatar/");
        }

        @Test
        @DisplayName("未知 content-type 兜底为 .png，避免生成无扩展名对象")
        void unknownTypeFallsBack() {
            assertThat(FileValidator.buildObjectName("avatar", "image/unknown")).endsWith(".png");
            assertThat(FileValidator.buildObjectName("avatar", null)).endsWith(".png");
        }

        @Test
        @DisplayName("按 yyyy/MM/dd 归档，路径中包含日期层级")
        void pathIsDatePartitioned() {
            String objectName = FileValidator.buildObjectName("review", "image/png");
            assertThat(objectName).matches("review/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.png");
        }

        @ParameterizedTest
        @ValueSource(strings = {"hacker", "../etc", "", " "})
        @DisplayName("业务目录不在白名单时回落到 common，防止路径穿越")
        void invalidBizFallsBackToCommon(String biz) {
            assertThat(FileValidator.buildObjectName(biz, "image/png")).startsWith("common/");
        }

        @Test
        @DisplayName("每次生成的对象名必须唯一，避免覆盖已上传文件")
        void objectNamesAreUnique() {
            String first = FileValidator.buildObjectName("common", "image/png");
            String second = FileValidator.buildObjectName("common", "image/png");
            assertThat(first).isNotEqualTo(second);
        }
    }
}
