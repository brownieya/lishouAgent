package com.brownie.lishouagent.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeUploadServiceTest {
    @TempDir
    Path directory;

    private KnowledgeFileStorage storage;
    private KnowledgeUploadService uploads;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        storage = new KnowledgeFileStorage(directory.toString());
        jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        uploads = new KnowledgeUploadService(storage, jdbc, manager);
    }

    @Test
    void markdownUploadStagesUtf8FileWithoutEmbedding() throws Exception {
        var file = upload("guide.md", "# 公司操作手册\n正文".getBytes(StandardCharsets.UTF_8));

        var result = uploads.upload(file, UUID.randomUUID());

        assertThat(result.format()).isEqualTo("MARKDOWN");
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().getFirst().relativePath()).isEqualTo("guide.md");
        assertThat(result.originalStorageKey()).isEqualTo(result.files().getFirst().storageKey());
        assertThat(Files.readString(storage.pathFor(result.originalStorageKey()))).contains("公司操作手册");
    }

    @Test
    void zipPreservesDirectoryPathsAndSeparatesImageStorage() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        var file = upload("wiki.zip", zip(Map.of(
                "示例项目/接口说明.md", "# 接口说明\n![图](images/示意.png)".getBytes(StandardCharsets.UTF_8),
                "示例项目/images/示意.png", png)));

        var result = uploads.upload(file, UUID.randomUUID());

        assertThat(result.format()).isEqualTo("ZIP");
        assertThat(result.files()).extracting(KnowledgeUploadService.UploadFile::relativePath)
                .containsExactlyInAnyOrder("示例项目/接口说明.md", "示例项目/images/示意.png");
        assertThat(result.files()).extracting(KnowledgeUploadService.UploadFile::fileType)
                .containsExactlyInAnyOrder("MARKDOWN", "IMAGE");
        assertThat(result.files()).allSatisfy(item -> assertThat(storage.pathFor(item.storageKey())).exists());
        assertThat(result.files()).allSatisfy(item ->
                assertThat(item.storageKey()).isNotEqualTo(result.originalStorageKey()));
    }

    @Test
    void unsafeOrRepeatedZipPathsLeaveNoFilesBehind() throws Exception {
        for (String path : new String[]{"../outside.md", "/absolute.md", "C:/drive.md", "folder\\unsafe.md",
                "folder/./unsafe.md"}) {
            assertThatThrownBy(() -> uploads.upload(upload("wiki.zip", zip(Map.of(path,
                    "# 文档".getBytes(StandardCharsets.UTF_8)))), UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertStorageEmpty();
        }
        var duplicate = new LinkedHashMap<String, byte[]>();
        duplicate.put("A.md", "# A".getBytes(StandardCharsets.UTF_8));
        duplicate.put("a.md", "# B".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> uploads.upload(upload("wiki.zip", zip(duplicate)), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertStorageEmpty();
    }

    @Test
    void rejectsWrongContentAndUnsupportedFilesWithoutLeavingStagedFiles() throws Exception {
        assertThatThrownBy(() -> uploads.upload(upload("empty.md", "  ".getBytes(StandardCharsets.UTF_8)),
                UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertStorageEmpty();

        assertThatThrownBy(() -> uploads.upload(upload("wiki.zip", zip(Map.of(
                "guide.md", "# 文档".getBytes(StandardCharsets.UTF_8),
                "image.png", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
        assertStorageEmpty();

        assertThatThrownBy(() -> uploads.upload(upload("archive.exe", new byte[]{1}), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertStorageEmpty();
    }

    @Test
    void databaseFailureRemovesUploadedFile() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> uploads.upload(upload("guide.md", "# 正文".getBytes(StandardCharsets.UTF_8)),
                UUID.randomUUID())).isInstanceOf(DataAccessResourceFailureException.class);
        assertStorageEmpty();
    }

    private void assertStorageEmpty() throws IOException {
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    private static MockMultipartFile upload(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                output.putNextEntry(new ZipEntry(file.getKey()));
                output.write(file.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
