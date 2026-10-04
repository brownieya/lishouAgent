package com.brownie.lishouagent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalWikiDocumentLoaderTest {
    @TempDir
    Path directory;

    @Test
    void importsNestedMarkdownWithRelativeSourcesAndKeepsImageAndTableReferences() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("业务"));
        Files.writeString(nested.resolve("部署.MD"), """
                # 部署流程

                先提交发布申请，然后部署服务。

                | 阶段 | 负责人 |
                | --- | --- |
                | 发布 | 项目负责人 |

                ![架构图](./assets/architecture.png)
                """);
        Files.writeString(directory.resolve("无标题.md"), "公司知识库的说明。");
        Files.write(directory.resolve("image.png"), new byte[]{1, 2, 3});

        var loader = new LocalWikiDocumentLoader(directory);
        var chunks = loader.load();
        assertThat(chunks).hasSize(2);
        var deployment = chunks.stream()
                .filter(chunk -> "业务/部署.MD".equals(chunk.getMetadata().get("sourcePath")))
                .findFirst().orElseThrow();
        assertThat(deployment.getMetadata()).containsEntry("title", "部署流程")
                .containsEntry("section", "部署流程").containsEntry("chunkIndex", 0);
        assertThat(deployment.getMetadata().get("updatedAt")).isNotNull();
        assertThat(deployment.getText()).contains("| 阶段 | 负责人 |", "./assets/architecture.png", "发布申请");
        assertThat(deployment.getMetadata().get("sourcePath").toString()).doesNotContain(directory.toString());
        assertThat(UUID.fromString(deployment.getId())).isNotNull();

        assertThat(loader.load()).extracting(chunk -> chunk.getId())
                .containsExactlyElementsOf(chunks.stream().map(chunk -> chunk.getId()).toList());
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("title", "无标题.md"));
    }

    @Test
    void missingOrEmptySourcesFailBeforeAnyModelCall() {
        assertThatThrownBy(() -> new LocalWikiDocumentLoader(directory.resolve("missing")).load())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> new LocalWikiDocumentLoader(directory).load())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Markdown");
    }

    @Test
    void oneBlankMarkdownRejectsWholeImportRatherThanLeavingItsOldVectorsBehind() throws Exception {
        Files.writeString(directory.resolve("有效.md"), "# 部署流程\n发布前需要审批。");
        Files.writeString(directory.resolve("空白.md"), " \n\t");
        assertThatThrownBy(() -> new LocalWikiDocumentLoader(directory).load())
                .isInstanceOf(IllegalStateException.class);
    }
}
