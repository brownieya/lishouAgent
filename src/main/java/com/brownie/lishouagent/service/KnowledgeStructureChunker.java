package com.brownie.lishouagent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeStructureChunker {
    private static final Pattern ENDPOINT = Pattern.compile("/api/[A-Za-z0-9/_-]+");
    private final int maxChars;
    private final int overlapChars;

    public KnowledgeStructureChunker(@Value("${lishou.knowledge.chunk.max-chars:1600}") int maxChars,
            @Value("${lishou.knowledge.chunk.overlap-chars:120}") int overlapChars) {
        if (maxChars < 80 || maxChars > 10000 || overlapChars < 0 || overlapChars >= maxChars / 2) {
            throw new IllegalArgumentException("Invalid knowledge chunk configuration");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    // 上传预览接口调用：按同一套规则切分解析结果，不写向量或改动已上传文件。
    public ChunkPreview preview(KnowledgeDocumentParseService.ParseResult parsed) {
        List<DocumentChunks> documents = parsed.documents().stream().map(document ->
                new DocumentChunks(document.relativePath(), document.title(), projectName(document.relativePath()),
                        chunk(document))).toList();
        return new ChunkPreview(parsed.uploadId(), maxChars, overlapChars, documents);
    }

    // 本地 Wiki 导入和上传预览共用：按二级标题分节，再按结构块组合切片，保留行号与章节信息。
    public List<Chunk> chunk(KnowledgeDocumentParseService.ParsedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections(document)) {
            String endpoint = endpoint(section.blocks());
            Pending pending = new Pending();
            for (KnowledgeDocumentParseService.Block block : section.blocks()) {
                if (block.type().equals("HEADING")) {
                    if (headingLevel(block.text()) >= 3 && !pending.empty()) {
                        chunks.add(pending.finish(chunks.size(), section.title(), endpoint, false));
                        pending = new Pending();
                    }
                    continue;
                }
                if (block.type().equals("PARAGRAPH") && block.text().length() > maxChars) {
                    if (!pending.empty()) {
                        chunks.add(pending.finish(chunks.size(), section.title(), endpoint, false));
                        pending = new Pending();
                    }
                    splitParagraph(block, section.title(), endpoint, chunks);
                    continue;
                }
                if (!pending.empty() && pending.length() + 2 + block.text().length() > maxChars) {
                    chunks.add(pending.finish(chunks.size(), section.title(), endpoint, false));
                    pending = new Pending();
                }
                if (block.text().length() > maxChars) {
                    pending.add(block);
                    chunks.add(pending.finish(chunks.size(), section.title(), endpoint, true));
                    pending = new Pending();
                } else {
                    pending.add(block);
                }
            }
            if (!pending.empty()) chunks.add(pending.finish(chunks.size(), section.title(), endpoint, false));
        }
        if (chunks.isEmpty()) {
            chunks.add(new Chunk(0, document.title(), document.title(), List.of(document.title()), null,
                    1, 1, List.of("HEADING"), false));
        }
        return chunks;
    }

    // chunk 的分节辅助步骤：二级标题隔开主题；首个“通用约定”与文档导言合并，保留基础规则上下文。
    private static List<Section> sections(KnowledgeDocumentParseService.ParsedDocument document) {
        List<Section> sections = new ArrayList<>();
        List<KnowledgeDocumentParseService.Block> current = new ArrayList<>();
        String title = document.title();
        boolean sawSecondLevel = false;
        for (KnowledgeDocumentParseService.Block block : document.blocks()) {
            if (block.type().equals("HEADING") && headingLevel(block.text()) == 2) {
                String nextTitle = block.headingPath().getLast();
                boolean mergePreface = !sawSecondLevel && nextTitle.equals("通用约定");
                if (hasContent(current) && !mergePreface) {
                    sections.add(new Section(title, List.copyOf(current)));
                    current.clear();
                }
                title = nextTitle;
                sawSecondLevel = true;
            }
            current.add(block);
        }
        if (!current.isEmpty()) sections.add(new Section(title, List.copyOf(current)));
        return sections;
    }

    private static boolean hasContent(List<KnowledgeDocumentParseService.Block> blocks) {
        return blocks.stream().anyMatch(block -> !block.type().equals("HEADING"));
    }

    private static int headingLevel(String text) {
        int level = 0;
        while (level < text.length() && text.charAt(level) == '#') level++;
        return level;
    }

    private static String endpoint(List<KnowledgeDocumentParseService.Block> blocks) {
        for (KnowledgeDocumentParseService.Block block : blocks) {
            Matcher match = ENDPOINT.matcher(block.text());
            if (match.find()) return match.group();
        }
        return null;
    }

    // chunk 的正文辅助步骤：只有超长普通正文使用字符重叠；步骤、表格和代码块即使超限也保持完整。
    private void splitParagraph(KnowledgeDocumentParseService.Block block, String section, String endpoint,
            List<Chunk> chunks) {
        String text = block.text();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + maxChars, text.length());
            int startLine = block.startLine() + newlines(text, 0, start);
            int endLine = block.startLine() + newlines(text, 0, end - 1);
            chunks.add(new Chunk(chunks.size(), text.substring(start, end), section, block.headingPath(), endpoint,
                    startLine, endLine, List.of("PARAGRAPH"), false));
            if (end == text.length()) break;
            start = end - overlapChars;
        }
    }

    private static int newlines(String text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    // 预览的项目名辅助步骤：仅使用上传包中的顶层目录，不根据文档正文猜测项目归属。
    static String projectName(String sourcePath) {
        int slash = sourcePath.indexOf('/');
        return slash > 0 ? sourcePath.substring(0, slash) : null;
    }

    private record Section(String title, List<KnowledgeDocumentParseService.Block> blocks) { }

    private static final class Pending {
        private final List<String> texts = new ArrayList<>();
        private final Set<String> types = new LinkedHashSet<>();
        private List<String> headingPath = List.of();
        private int startLine;
        private int endLine;
        private int length;

        private void add(KnowledgeDocumentParseService.Block block) {
            if (texts.isEmpty()) startLine = block.startLine();
            else length += 2;
            texts.add(block.text());
            types.add(block.type());
            headingPath = block.headingPath();
            endLine = block.endLine();
            length += block.text().length();
        }

        private boolean empty() { return texts.isEmpty(); }

        private int length() { return length; }

        private Chunk finish(int index, String section, String endpoint, boolean oversized) {
            return new Chunk(index, String.join("\n\n", texts), section, headingPath, endpoint,
                    startLine, endLine, List.copyOf(types), oversized);
        }
    }

    public record ChunkPreview(UUID uploadId, int maxChars, int overlapChars, List<DocumentChunks> documents) { }
    public record DocumentChunks(String relativePath, String title, String projectName, List<Chunk> chunks) { }
    public record Chunk(int index, String text, String section, List<String> headingPath, String endpoint,
                        int startLine, int endLine, List<String> blockTypes, boolean oversized) { }
}
