package com.geekmall.modules.content.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.geekmall.modules.content.support.LocalFileAccessSigner;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalFileAccessControllerTest {

    @TempDir
    Path tempDir;

    private LocalFileAccessSigner signer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        signer = new LocalFileAccessSigner("test-secret", "http://localhost:8080");
        LocalFileAccessController controller = new LocalFileAccessController(signer);
        ReflectionTestUtils.setField(controller, "localDir", tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static Map<String, String> queryOf(String url) {
        String rawQuery = URI.create(url).getRawQuery();
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("合法签名可以下载本地敏感文件")
    void downloadsSignedFile() throws Exception {
        String path = "aftersale/2026/10/21/evidence.png";
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, "evidence".getBytes(StandardCharsets.UTF_8));
        Map<String, String> query = queryOf(signer.signedUrl(path));

        mockMvc.perform(get("/api/v1/files/local")
                        .param("path", path)
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes("evidence".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("签名错误返回 403")
    void rejectsInvalidSignature() throws Exception {
        mockMvc.perform(get("/api/v1/files/local")
                        .param("path", "aftersale/evidence.png")
                        .param("expires", "9999999999")
                        .param("sig", "bad-signature"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("即使签名合法，也不允许路径逃逸出本地根目录")
    void rejectsPathTraversal() throws Exception {
        String outsideName = "outside-" + UUID.randomUUID() + ".txt";
        String path = "../" + outsideName;
        Files.write(tempDir.getParent().resolve(outsideName), "outside".getBytes(StandardCharsets.UTF_8));
        Map<String, String> query = queryOf(signer.signedUrl(path));

        mockMvc.perform(get("/api/v1/files/local")
                        .param("path", path)
                        .param("expires", query.get("expires"))
                        .param("sig", query.get("sig")))
                .andExpect(status().isNotFound());
    }
}
