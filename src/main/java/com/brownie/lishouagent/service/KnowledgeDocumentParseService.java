package com.brownie.lishouagent.service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentParseService {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern ORDERED_STEP = Pattern.compile("^ {0,3}\\d+[.)][ \\t]+.*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^ {0,3}[-*+][ \\t]+.*$");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[([^]]*)]\\(\\s*(?:<([^>]+)>|([^\\s)]+))(?:\\s+[^)]*)?\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile(
            "(?i)<img\\b[^>]*?\\bsrc\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))[^>]*>");
    private final KnowledgeUploadService uploads;
    private final KnowledgeFileStorage storage;

    public KnowledgeDocumentParseService(KnowledgeUploadService uploads, KnowledgeFileStorage storage) {
        this.uploads = uploads;
        this.storage = storage;
    }

    // 查询上传解析预览时调用：读取暂存 Markdown，按结构生成块并关联同一上传中的图片；不写数据库或向量。
    public ParseResult parse(UUID uploadId) throws IOException {
        KnowledgeUploadService.Upload upload = uploads.get(uploadId);
        Map<String, KnowledgeUploadService.UploadFile> images = new HashMap<>();
        for (KnowledgeUploadService.UploadFile file : upload.files()) {
            if (file.fileType().equals("IMAGE")) images.put(file.relativePath().toLowerCase(Locale.ROOT), file);
        }
        List<ParsedDocument> documents = new ArrayList<>();
        Set<String> linkedImages = new HashSet<>();
        for (KnowledgeUploadService.UploadFile file : upload.files()) {
            if (!file.fileType().equals("MARKDOWN")) continue;
            String markdown = Files.readString(storage.pathFor(file.storageKey()), StandardCharsets.UTF_8);
            documents.add(parseDocument(file.relativePath(), markdown, images, linkedImages));
        }
        List<String> unreferencedImages = upload.files().stream()
                .filter(file -> file.fileType().equals("IMAGE") && !linkedImages.contains(file.relativePath()))
                .map(KnowledgeUploadService.UploadFile::relativePath).sorted().toList();
        return new ParseResult(uploadId, documents, unreferencedImages);
    }

    // parse 的文档辅助步骤：把 Markdown 行归为标题、表格、步骤、列表、代码和正文块，保留标题层级与行号。
    private static ParsedDocument parseDocument(String path, String markdown,
            Map<String, KnowledgeUploadService.UploadFile> images, Set<String> linkedImages) {
        List<String> lines = markdown.lines().toList();
        List<Block> blocks = new ArrayList<>();
        List<ImageReference> references = new ArrayList<>();
        String[] headings = new String[6];
        String title = path.substring(path.lastIndexOf('/') + 1);
        for (int start = 0; start < lines.size();) {
            String line = lines.get(start);
            if (line.isBlank()) {
                start++;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            Matcher heading = HEADING.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                int end = start + 1;
                while (end < lines.size()) {
                    String closing = lines.get(end).strip();
                    end++;
                    if (closing.length() >= marker.length() && closing.chars().allMatch(ch -> ch == marker.charAt(0))) {
                        break;
                    }
                }
                blocks.add(block("CODE", lines, start, end, headings));
                start = end;
            } else if (heading.matches()) {
                int level = heading.group(1).length();
                String label = heading.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "").trim();
                headings[level - 1] = label;
                for (int i = level; i < headings.length; i++) headings[i] = null;
                if (level == 1 && title.equals(path.substring(path.lastIndexOf('/') + 1))) title = label;
                blocks.add(block("HEADING", lines, start, start + 1, headings));
                addImages(line, start + 1, path, images, linkedImages, references);
                start++;
            } else {
                String type = tableRow(line) ? "TABLE" : ORDERED_STEP.matcher(line).matches() ? "STEP"
                        : LIST_ITEM.matcher(line).matches() ? "LIST" : "PARAGRAPH";
                int end = start + 1;
                while (end < lines.size() && !lines.get(end).isBlank()
                        && !HEADING.matcher(lines.get(end)).matches() && !FENCE.matcher(lines.get(end)).matches()) {
                    String next = lines.get(end);
                    if (startsNewBlock(type, next)) break;
                    end++;
                }
                blocks.add(block(type, lines, start, end, headings));
                for (int i = start; i < end; i++) {
                    addImages(lines.get(i), i + 1, path, images, linkedImages, references);
                }
                start = end;
            }
        }
        return new ParsedDocument(path, title, blocks, references);
    }

    // parseDocument 的块辅助步骤：保留原始 Markdown 和标题路径，供后续结构切片判断边界。
    private static Block block(String type, List<String> lines, int start, int end, String[] headings) {
        List<String> headingPath = new ArrayList<>();
        for (String heading : headings) if (heading != null) headingPath.add(heading);
        return new Block(type, String.join("\n", lines.subList(start, end)), headingPath, start + 1, end);
    }

    private static boolean tableRow(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("|") && trimmed.indexOf('|', 1) >= 0;
    }

    // parseDocument 的块边界辅助步骤：在连续非空行中仍分开表格和每一条操作步骤。
    private static boolean startsNewBlock(String currentType, String line) {
        if (tableRow(line)) return !currentType.equals("TABLE");
        if (currentType.equals("TABLE")) return true;
        return ORDERED_STEP.matcher(line).matches() || LIST_ITEM.matcher(line).matches();
    }

    // parseDocument 的图片辅助步骤：提取 Markdown/HTML 图片地址，并仅从本次上传清单中解析本地图片。
    private static void addImages(String line, int lineNumber, String documentPath,
            Map<String, KnowledgeUploadService.UploadFile> images, Set<String> linkedImages,
            List<ImageReference> references) {
        Matcher markdown = MARKDOWN_IMAGE.matcher(line);
        while (markdown.find()) {
            String target = markdown.group(2) != null ? markdown.group(2) : markdown.group(3);
            references.add(resolveImage(documentPath, lineNumber, markdown.group(1), target, images, linkedImages));
        }
        Matcher html = HTML_IMAGE.matcher(line);
        while (html.find()) {
            String target = html.group(1) != null ? html.group(1) : html.group(2) != null ? html.group(2) : html.group(3);
            references.add(resolveImage(documentPath, lineNumber, "", target, images, linkedImages));
        }
    }

    // addImages 的路径辅助步骤：按 Markdown 所在目录解析相对地址，阻止越界路径被误关联到其他文件。
    private static ImageReference resolveImage(String documentPath, int line, String alt, String target,
            Map<String, KnowledgeUploadService.UploadFile> images, Set<String> linkedImages) {
        if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*") || target.startsWith("//")) {
            return new ImageReference(target, alt, null, null, "EXTERNAL", line);
        }
        String path;
        try {
            path = URLDecoder.decode(target.split("[?#]", 2)[0].replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return new ImageReference(target, alt, null, null, "INVALID_PATH", line);
        }
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            return new ImageReference(target, alt, null, null, "INVALID_PATH", line);
        }
        List<String> segments = new ArrayList<>();
        String parent = documentPath.substring(0, Math.max(0, documentPath.lastIndexOf('/') + 1));
        for (String segment : (parent + path).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return new ImageReference(target, alt, null, null, "INVALID_PATH", line);
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        String relativePath = String.join("/", segments);
        KnowledgeUploadService.UploadFile image = images.get(relativePath.toLowerCase(Locale.ROOT));
        if (image == null) return new ImageReference(target, alt, relativePath, null, "MISSING", line);
        linkedImages.add(image.relativePath());
        return new ImageReference(target, alt, image.relativePath(), image.storageKey(), "LINKED", line);
    }

    public record ParseResult(UUID uploadId, List<ParsedDocument> documents, List<String> unreferencedImages) { }
    public record ParsedDocument(String relativePath, String title, List<Block> blocks,
                                 List<ImageReference> images) { }
    public record Block(String type, String text, List<String> headingPath, int startLine, int endLine) { }
    public record ImageReference(String target, String alt, String relativePath, String storageKey,
                                 String status, int line) { }
}
