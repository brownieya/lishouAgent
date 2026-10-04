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
                metadata.put("section", title);
                metadata.put("updatedAt", Files.getLastModifiedTime(file).toInstant().toString());
                // Read raw UTF-8 Markdown so tables, code, quotations and image references are preserved.
                List<Document> split = splitter.apply(List.of(new Document(text, metadata)));
                for (int index = 0; index < split.size(); index++) {
                    var chunkMetadata = new HashMap<>(metadata);
                    chunkMetadata.put("chunkIndex", index);
                    chunks.add(Document.builder().id(stableId(documentId + ":" + index))
                            .text(split.get(index).getText()).metadata(chunkMetadata).build());
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
