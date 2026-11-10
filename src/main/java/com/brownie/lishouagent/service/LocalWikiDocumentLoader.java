package com.brownie.lishouagent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit local Markdown ingestion; construction never reads or embeds Wiki data. */
@Component
public class LocalWikiDocumentLoader {
    private final Path sourceDirectory;
    private final KnowledgeStructureChunker chunker;

    @Autowired
    public LocalWikiDocumentLoader(@Value("${lishou.knowledge.source-dir:./data/wiki/source}") String directory,
            KnowledgeStructureChunker chunker) {
        this(Path.of(directory), chunker);
    }

    public LocalWikiDocumentLoader(Path sourceDirectory) {
        this(sourceDirectory, new KnowledgeStructureChunker(1600, 120));
    }

    LocalWikiDocumentLoader(Path sourceDirectory, KnowledgeStructureChunker chunker) {
        this.sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
        this.chunker = chunker;
    }

    public List<Document> load() {
        if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Wiki source directory is missing");
        }
        try {
            Path realRoot = sourceDirectory.toRealPath();
            List<Path> markdownFiles;
            try (var paths = Files.walk(realRoot)) {
                markdownFiles = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .sorted().toList();
            }
            List<Document> chunks = new ArrayList<>();
            for (Path file : markdownFiles) {
                if (!file.toRealPath().startsWith(realRoot)) {
                    throw new IllegalStateException("Wiki file is outside the configured source directory");
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.isBlank()) {
                    throw new IllegalStateException("Empty Markdown document; import was not started");
                }
                String sourcePath = realRoot.relativize(file).toString().replace('\\', '/');
                String documentId = stableId("wiki:" + sourcePath);
                var parsed = KnowledgeDocumentParseService.parseMarkdown(sourcePath, text);
                String title = parsed.title();
                var metadata = new HashMap<String, Object>();
                metadata.put("documentId", documentId);
                metadata.put("sourcePath", sourcePath);
                metadata.put("title", title);
                metadata.put("updatedAt", Files.getLastModifiedTime(file).toInstant().toString());
                String projectName = KnowledgeStructureChunker.projectName(sourcePath);
                if (projectName != null) metadata.put("projectName", projectName);
                // 显式导入时用与上传预览相同的结构切片规则；保留接口与来源信息，防止跨接口混答。
                for (KnowledgeStructureChunker.Chunk chunk : chunker.chunk(parsed)) {
                    var chunkMetadata = new HashMap<>(metadata);
                    chunkMetadata.put("section", chunk.section());
                    chunkMetadata.put("headingPath", chunk.headingPath());
                    chunkMetadata.put("sourceStartLine", chunk.startLine());
                    chunkMetadata.put("sourceEndLine", chunk.endLine());
                    chunkMetadata.put("blockTypes", chunk.blockTypes());
                    chunkMetadata.put("oversized", chunk.oversized());
                    chunkMetadata.put("chunkIndex", chunk.index());
                    if (chunk.endpoint() != null) chunkMetadata.put("endpoint", chunk.endpoint());
                    String context = "文档：" + title
                            + (projectName == null ? "" : "\n项目：" + projectName)
                            + "\n章节：" + chunk.section()
                            + "\n标题路径：" + String.join(" > ", chunk.headingPath())
                            + (chunk.endpoint() == null ? "" : "\n接口：" + chunk.endpoint()) + "\n\n";
                    chunks.add(Document.builder().id(stableId(documentId + ":" + chunk.index()))
                            .text(context + chunk.text()).metadata(chunkMetadata).build());
                }
            }
            if (chunks.isEmpty()) throw new IllegalStateException("No non-empty Markdown documents found");
            return chunks;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read local Wiki documents", exception);
        }
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

}
