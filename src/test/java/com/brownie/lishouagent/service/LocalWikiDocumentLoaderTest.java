package com.brownie.lishouagent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.document.Document;
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

    @Test
    void interfaceChunksKeepTheirOwnEndpointAndNeverIncludeTheNextInterface() throws Exception {
        StringBuilder markdown = new StringBuilder("""
                # 接口规范

                ## 接口 1：订单下发
                - URL：`/api/v1/demo/order/receive`
                - 请求体为订单数组，明细在 items 中。
                """);
        for (int index = 0; index < 180; index++) {
            markdown.append("| order_field_").append(index).append(" | 订单字段说明 |\n");
        }
        markdown.append("""

                ## 接口 2：排程反馈
                - URL：`/api/v1/demo/schedule/feedback`
                - 请求体包含 data 数组和 schedule_field 字段。
                """);
        Files.writeString(directory.resolve("接口规范.md"), markdown);

        List<Document> chunks = new LocalWikiDocumentLoader(directory).load();
        List<Document> orderChunks = chunks.stream()
                .filter(chunk -> "/api/v1/demo/order/receive".equals(chunk.getMetadata().get("endpoint"))).toList();
        assertThat(orderChunks).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> assertThat(chunk.getText())
                        .contains("/api/v1/demo/order/receive")
                        .doesNotContain("排程反馈", "schedule_field"));
        assertThat(orderChunks).allSatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("section", "接口 1：订单下发"));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("endpoint", "/api/v1/demo/schedule/feedback")
                .containsEntry("section", "接口 2：排程反馈"));
    }

    @Test
    void documentPrefaceAndFirstCommonConventionsRemainInOneChunk() throws Exception {
        Files.writeString(directory.resolve("接口规范.md"), """
                # 接口规范
                > Base URL：待定

                ## 通用约定
                - 认证方式：待定

                ## 接口 1：订单下发
                - URL：`/api/v1/demo/order/receive`
                """);

        List<Document> chunks = new LocalWikiDocumentLoader(directory).load();
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("section", "通用约定").doesNotContainKey("endpoint");
            assertThat(chunk.getText()).contains("Base URL：待定", "认证方式：待定")
                    .doesNotContain("/api/v1/demo/order/receive");
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("section", "接口 1：订单下发")
                .containsEntry("endpoint", "/api/v1/demo/order/receive"));
    }
}
