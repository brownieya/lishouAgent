package com.brownie.lishouagent.service;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeStructureChunkerTest {
    @Test
    void keepsCommonConventionsAndSeparatesInterfaceSections() {
        String markdown = """
                # 接口规范
                > Base URL：待定
                ## 通用约定
                认证方式：待定
                ## 接口一
                - URL：`/api/v1/demo/order/receive`
                1. 提交订单
                   确认订单编码。
                | 字段 | 说明 |
                | --- | --- |
                | code | 订单编码，提交时必须保持原样 |
                | items | 订单明细，用于确认全部零件 |
                | note | 这是一段较长的字段说明，要求整个表格作为同一个结构块保存，超过普通切片长度时仍不能拆开 |
                ```json
                {"code":"EXAMPLE"}
                ```
                ## 接口二
                - URL：`/api/v1/demo/schedule/feedback`
                排程反馈内容。
                """;
        var document = KnowledgeDocumentParseService.parseMarkdown("示例项目/接口.md", markdown);
        var chunks = new KnowledgeStructureChunker(80, 10).chunk(document);

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.section()).isEqualTo("通用约定");
            assertThat(chunk.text()).contains("Base URL：待定", "认证方式：待定");
            assertThat(chunk.endpoint()).isNull();
        });
        var first = chunks.stream().filter(chunk -> "/api/v1/demo/order/receive".equals(chunk.endpoint())).toList();
        assertThat(first).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.section()).isEqualTo("接口一");
            assertThat(chunk.text()).doesNotContain("排程反馈", "/api/v1/demo/schedule/feedback");
        });
        assertThat(first).anySatisfy(chunk -> {
            assertThat(chunk.blockTypes()).contains("STEP");
            assertThat(chunk.text()).contains("1. 提交订单", "确认订单编码。");
        });
        assertThat(first).anySatisfy(chunk -> {
            assertThat(chunk.blockTypes()).contains("TABLE");
            assertThat(chunk.text()).contains("| 字段 | 说明 |", "| items | 订单明细");
            assertThat(chunk.oversized()).isTrue();
        });
        assertThat(first).anySatisfy(chunk -> assertThat(chunk.text())
                .contains("```json", "{\"code\":\"EXAMPLE\"}", "```"));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.endpoint())
                .isEqualTo("/api/v1/demo/schedule/feedback"));
        assertThat(chunks).extracting(KnowledgeStructureChunker.Chunk::index)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void splitsOnlyLongParagraphWithConfiguredOverlap() {
        String body = "甲".repeat(170);
        var document = KnowledgeDocumentParseService.parseMarkdown("示例/说明.md", "# 说明\n" + body);
        var chunker = new KnowledgeStructureChunker(80, 10);

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(3).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(80);
            assertThat(chunk.blockTypes()).containsExactly("PARAGRAPH");
            assertThat(chunk.headingPath()).containsExactly("说明");
            assertThat(chunk.startLine()).isEqualTo(2);
        });
        assertThat(chunks.get(1).text().substring(0, 10))
                .isEqualTo(chunks.getFirst().text().substring(chunks.getFirst().text().length() - 10));
        assertThat(chunks.get(2).text().substring(0, 10))
                .isEqualTo(chunks.get(1).text().substring(chunks.get(1).text().length() - 10));
        var preview = chunker.preview(new KnowledgeDocumentParseService.ParseResult(UUID.randomUUID(),
                List.of(document), List.of()));
        assertThat(preview.documents().getFirst().projectName()).isEqualTo("示例");
        assertThat(preview.overlapChars()).isEqualTo(10);
    }

    @Test
    void splitsUnindentedDescriptionAfterEndpointList() {
        String markdown = "# 接口规范\n## 接口二\n- URL：`/api/v1/demo/schedule/feedback`\n" + "甲".repeat(170);
        var document = KnowledgeDocumentParseService.parseMarkdown("示例/接口.md", markdown);
        var chunks = new KnowledgeStructureChunker(80, 10).chunk(document);

        assertThat(document.blocks()).extracting(KnowledgeDocumentParseService.Block::type)
                .containsExactly("HEADING", "HEADING", "LIST", "PARAGRAPH");
        assertThat(chunks.getFirst().text()).contains("/api/v1/demo/schedule/feedback");
        var bodyChunks = chunks.stream().filter(chunk -> chunk.blockTypes().contains("PARAGRAPH")).toList();
        assertThat(bodyChunks).hasSize(3).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(80);
            assertThat(chunk.endpoint()).isEqualTo("/api/v1/demo/schedule/feedback");
            assertThat(chunk.oversized()).isFalse();
        });
        assertThat(bodyChunks.get(1).text().substring(0, 10)).isEqualTo(bodyChunks.getFirst().text().substring(70));
        assertThat(bodyChunks.get(2).text().substring(0, 10)).isEqualTo(bodyChunks.get(1).text().substring(70));
    }

    @Test
    void rejectsUnsafeChunkConfiguration() {
        assertThatThrownBy(() -> new KnowledgeStructureChunker(79, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeStructureChunker(80, 40))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
