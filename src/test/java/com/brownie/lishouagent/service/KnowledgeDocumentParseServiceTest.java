package com.brownie.lishouagent.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentParseServiceTest {
    @TempDir
    Path directory;

    @Test
    void parsesStructureAndLinksImagesWithinUploadedPackage() throws Exception {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(directory.toString());
        String markdown = """
                # 操作说明
                简介文字。

                ## 操作步骤
                1. 打开页面
                   查看图片 ![流程](images/demo%20image.png)
                2. 保存结果
                | 参数 | 说明 |
                | --- | --- |
                | code | 编码 |

                ```md
                ![不应识别](missing.png)
                ```
                """;
        var document = file(storage, "guide.md", "MARKDOWN", markdown.getBytes(StandardCharsets.UTF_8));
        var image = file(storage, "demo.png", "IMAGE", new byte[]{1, 2, 3});
        var unused = file(storage, "unused.png", "IMAGE", new byte[]{4, 5});
        UUID uploadId = UUID.randomUUID();
        KnowledgeUploadService uploads = mock(KnowledgeUploadService.class);
        when(uploads.get(uploadId)).thenReturn(new KnowledgeUploadService.Upload(uploadId, "wiki.zip", "ZIP",
                UUID.randomUUID().toString(), List.of(
                        withPath(document, "项目/guide.md"), withPath(image, "项目/images/demo image.png"),
                        withPath(unused, "项目/images/unused.png"))));

        var parsed = new KnowledgeDocumentParseService(uploads, storage).parse(uploadId);

        assertThat(parsed.documents()).hasSize(1);
        var result = parsed.documents().getFirst();
        assertThat(result.title()).isEqualTo("操作说明");
        assertThat(result.blocks()).extracting(KnowledgeDocumentParseService.Block::type)
                .containsExactly("HEADING", "PARAGRAPH", "HEADING", "STEP", "STEP", "TABLE", "CODE");
        assertThat(result.blocks().get(3).headingPath()).containsExactly("操作说明", "操作步骤");
        assertThat(result.blocks().get(5).text()).contains("| --- | --- |", "| code | 编码 |");
        assertThat(result.blocks().get(6).text()).contains("![不应识别](missing.png)");
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().getFirst().status()).isEqualTo("LINKED");
        assertThat(result.images().getFirst().relativePath()).isEqualTo("项目/images/demo image.png");
        assertThat(result.images().getFirst().storageKey()).isEqualTo(image.storageKey());
        assertThat(parsed.unreferencedImages()).containsExactly("项目/images/unused.png");
    }

    @Test
    void reportsMissingExternalAndEscapingImageReferences() throws Exception {
        KnowledgeFileStorage storage = new KnowledgeFileStorage(directory.toString());
        String markdown = """
                # 说明
                ![缺失](images/missing.png)
                ![外部](https://example.org/image.png)
                ![越界](../../outside.png)
                <img src="images/also-missing.jpg">
                """;
        var document = file(storage, "guide.md", "MARKDOWN", markdown.getBytes(StandardCharsets.UTF_8));
        UUID uploadId = UUID.randomUUID();
        KnowledgeUploadService uploads = mock(KnowledgeUploadService.class);
        when(uploads.get(uploadId)).thenReturn(new KnowledgeUploadService.Upload(uploadId, "guide.md", "MARKDOWN",
                document.storageKey(), List.of(withPath(document, "项目/guide.md"))));

        var references = new KnowledgeDocumentParseService(uploads, storage).parse(uploadId)
                .documents().getFirst().images();

        assertThat(references).extracting(KnowledgeDocumentParseService.ImageReference::status)
                .containsExactly("MISSING", "EXTERNAL", "INVALID_PATH", "MISSING");
        assertThat(references.getFirst().relativePath()).isEqualTo("项目/images/missing.png");
        assertThat(references.get(2).storageKey()).isNull();
    }

    private static KnowledgeUploadService.UploadFile file(KnowledgeFileStorage storage, String name,
            String type, byte[] content) throws Exception {
        var saved = storage.store(name, new ByteArrayInputStream(content));
        return new KnowledgeUploadService.UploadFile(name, type, saved.storageKey(), saved.size(), saved.sha256());
    }

    private static KnowledgeUploadService.UploadFile withPath(KnowledgeUploadService.UploadFile file, String path) {
        return new KnowledgeUploadService.UploadFile(path, file.fileType(), file.storageKey(), file.size(), file.sha256());
    }
}
