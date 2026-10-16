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
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit local Markdown ingestion; construction never reads or embeds Wiki data. */
@Component
public class LocalWikiDocumentLoader {
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern SECTION = Pattern.compile("(?m)^##[ \\t]+([^\\r\\n]+)");
    private static final Pattern ENDPOINT = Pattern.compile("/api/[A-Za-z0-9/_-]+");
    private final Path sourceDirectory;

    @Autowired
    public LocalWikiDocumentLoader(@Value("${lishou.knowledge.source-dir:./data/wiki/source}") String directory) {
        this(Path.of(directory));
    }

    public LocalWikiDocumentLoader(Path sourceDirectory) {
        this.sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
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
            TokenTextSplitter splitter = new TokenTextSplitter();
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
                var matcher = TITLE.matcher(text);
                String title = matcher.find() ? matcher.group(1).trim() : file.getFileName().toString();
                var metadata = new HashMap<String, Object>();
                metadata.put("documentId", documentId);
                metadata.put("sourcePath", sourcePath);
                metadata.put("title", title);
                metadata.put("updatedAt", Files.getLastModifiedTime(file).toInstant().toString());
                int chunkIndex = 0;
                // 按二级标题先分章节，再限长切片，避免相邻接口的字段进入同一切片。
                for (WikiSection section : sections(text, title)) {
                    var sectionMetadata = new HashMap<>(metadata);
                    sectionMetadata.put("section", section.title());
                    String endpoint = endpoint(section.text());
                    if (endpoint != null) sectionMetadata.put("endpoint", endpoint);
                    List<Document> split = splitter.apply(List.of(new Document(section.text(), sectionMetadata)));
                    for (Document part : split) {
                        var chunkMetadata = new HashMap<>(sectionMetadata);
                        chunkMetadata.put("chunkIndex", chunkIndex);
                        String context = "文档：" + title + "\n章节：" + section.title()
                                + (endpoint == null ? "" : "\n接口：" + endpoint) + "\n\n";
                        chunks.add(Document.builder().id(stableId(documentId + ":" + chunkIndex))
                                .text(context + part.getText()).metadata(chunkMetadata).build());
                        chunkIndex++;
                    }
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

    // load 的辅助方法：按二级标题划分章节；导言与紧邻的通用约定一起提供完整规范。
    private static List<WikiSection> sections(String text, String title) {
        var sections = new ArrayList<WikiSection>();
        var matcher = SECTION.matcher(text);
        int start = 0;
        String sectionTitle = title;
        boolean firstHeading = true;
        while (matcher.find()) {
            String heading = matcher.group(1).trim();
            if (firstHeading && heading.equals("通用约定")) {
                sectionTitle = heading;
                firstHeading = false;
                continue;
            }
            if (!text.substring(start, matcher.start()).isBlank()) {
                sections.add(new WikiSection(sectionTitle, text.substring(start, matcher.start())));
            }
            start = matcher.start();
            sectionTitle = heading;
            firstHeading = false;
        }
        sections.add(new WikiSection(sectionTitle, text.substring(start)));
        return sections;
    }

    // load 的辅助方法：从一个章节中提取接口路径，供检索时按接口限定来源。
    private static String endpoint(String text) {
        var matcher = ENDPOINT.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private record WikiSection(String title, String text) {}
}
