package com.brownie.lishouagent.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeUploadService {
    private static final int MAX_ZIP_ENTRIES = 500;
    private static final long MAX_EXTRACTED_BYTES = 100L * 1024 * 1024;
    private final KnowledgeFileStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public KnowledgeUploadService(KnowledgeFileStorage storage, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    // 上传接口调用：先校验并保存 Markdown/ZIP 文件，再原子记录上传清单；此处不生成向量。
    public Upload upload(MultipartFile file, UUID createdBy) throws IOException {
        String originalName = file.getOriginalFilename();
        if (originalName == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空 Markdown 或 ZIP 文件");
        String name = originalName.toLowerCase(Locale.ROOT);
        String format = name.endsWith(".md") ? "MARKDOWN" : name.endsWith(".zip") ? "ZIP" : null;
        if (format == null) throw new IllegalArgumentException("只支持 Markdown 或 ZIP 文件");

        List<String> savedKeys = new ArrayList<>();
        try {
            KnowledgeFileStorage.StoredFile original;
            try (InputStream input = file.getInputStream()) {
                original = storage.store(originalName, input);
            }
            savedKeys.add(original.storageKey());
            List<StoredEntry> entries;
            if (format.equals("MARKDOWN")) {
                validateMarkdown(storage.pathFor(original.storageKey()));
                entries = List.of(new StoredEntry(originalName, "MARKDOWN", original));
            } else {
                entries = extractZip(original.storageKey(), savedKeys);
            }

            UUID uploadId = UUID.randomUUID();
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO public.knowledge_upload"
                                + "(id,original_filename,file_format,original_storage_key,created_by) VALUES (?,?,?,?,?)",
                        uploadId, originalName, format, UUID.fromString(original.storageKey()), createdBy);
                for (StoredEntry entry : entries) {
                    jdbc.update("INSERT INTO public.knowledge_upload_file"
                                    + "(id,upload_id,relative_path,file_type,storage_key,byte_size,content_sha256)"
                                    + " VALUES (?,?,?,?,?,?,?)",
                            UUID.randomUUID(), uploadId, entry.relativePath(), entry.fileType(),
                            UUID.fromString(entry.file().storageKey()), entry.file().size(), entry.file().sha256());
                }
            });
            return new Upload(uploadId, originalName, format, original.storageKey(),
                    entries.stream().map(entry -> new UploadFile(entry.relativePath(), entry.fileType(),
                            entry.file().storageKey(), entry.file().size(), entry.file().sha256())).toList());
        } catch (ZipException exception) {
            deleteSaved(savedKeys);
            throw new IllegalArgumentException("ZIP 文件损坏或不支持", exception);
        } catch (IOException | RuntimeException exception) {
            deleteSaved(savedKeys);
            throw exception;
        }
    }

    // 查询接口调用：从数据库恢复上传清单，后端重启后仍能追溯原文件和 ZIP 内的相对路径。
    public Upload get(UUID id) {
        List<Upload> uploads = jdbc.query("SELECT id,original_filename,file_format,original_storage_key"
                        + " FROM public.knowledge_upload WHERE id=?",
                (row, index) -> new Upload(row.getObject("id", UUID.class), row.getString("original_filename"),
                        row.getString("file_format"), row.getObject("original_storage_key", UUID.class).toString(),
                        List.of()), id);
        if (uploads.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上传记录不存在");
        Upload upload = uploads.getFirst();
        List<UploadFile> files = jdbc.query("SELECT relative_path,file_type,storage_key,byte_size,content_sha256"
                        + " FROM public.knowledge_upload_file WHERE upload_id=? ORDER BY relative_path",
                (row, index) -> new UploadFile(row.getString("relative_path"), row.getString("file_type"),
                        row.getObject("storage_key", UUID.class).toString(), row.getLong("byte_size"),
                        row.getString("content_sha256")), id);
        return new Upload(upload.id(), upload.originalFilename(), upload.format(), upload.originalStorageKey(), files);
    }

    // upload 的 ZIP 辅助步骤：逐条校验相对路径与内容，并按 UUID 保存文件，避免解压覆盖服务器路径。
    private List<StoredEntry> extractZip(String archiveKey, List<String> savedKeys) throws IOException {
        List<StoredEntry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long extractedBytes = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(storage.pathFor(archiveKey)),
                StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (++count > MAX_ZIP_ENTRIES) throw new IllegalArgumentException("ZIP 文件条目不能超过 500 个");
                String path = relativePath(entry.getName(), entry.isDirectory());
                if (entry.isDirectory()) continue;
                if (!paths.add(path.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("ZIP 内存在重复文件路径");
                }
                String type = fileType(path);
                String basename = path.substring(path.lastIndexOf('/') + 1);
                KnowledgeFileStorage.StoredFile stored = storage.store(basename, zip);
                savedKeys.add(stored.storageKey());
                extractedBytes += stored.size();
                if (extractedBytes > MAX_EXTRACTED_BYTES) {
                    throw new IllegalArgumentException("ZIP 解压后文件总量不能超过 100MB");
                }
                validateContent(type, path, storage.pathFor(stored.storageKey()));
                entries.add(new StoredEntry(path, type, stored));
            }
        }
        if (entries.stream().noneMatch(entry -> entry.fileType().equals("MARKDOWN"))) {
            throw new IllegalArgumentException("ZIP 必须包含 Markdown 文档");
        }
        return entries;
    }

    // extractZip 的路径辅助步骤：仅保留安全的相对路径作为元数据，不使用 ZIP 路径写磁盘。
    private static String relativePath(String raw, boolean directory) {
        if (raw == null || raw.isBlank() || raw.getBytes(StandardCharsets.UTF_8).length > 1000
                || raw.startsWith("/") || raw.contains("\\")) {
            throw new IllegalArgumentException("ZIP 内含无效路径");
        }
        String path = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..") || segment.contains(":")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.chars().anyMatch(ch -> Character.isISOControl(ch))) {
                throw new IllegalArgumentException("ZIP 内含无效路径");
            }
        }
        return path;
    }

    // extractZip 调用：按条目扩展名确定暂存类型，阻止不在首版范围内的文件进入清单。
    private static String fileType(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".md")) return "MARKDOWN";
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) return "IMAGE";
        throw new IllegalArgumentException("ZIP 只允许 Markdown、PNG 和 JPEG 文件");
    }

    // extractZip 调用：校验 Markdown 与图片文件头，避免仅凭 ZIP 内扩展名接受伪装文件。
    private static void validateContent(String type, String relativePath, Path file) throws IOException {
        if (type.equals("MARKDOWN")) {
            validateMarkdown(file);
            return;
        }
        byte[] header;
        try (InputStream input = Files.newInputStream(file)) {
            header = input.readNBytes(8);
        }
        boolean png = header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G' && header[4] == 13 && header[5] == 10
                && header[6] == 26 && header[7] == 10;
        boolean jpeg = header.length >= 3 && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
        boolean expectedPng = relativePath.toLowerCase(Locale.ROOT).endsWith(".png");
        if (expectedPng ? !png : !jpeg) {
            throw new IllegalArgumentException("图片扩展名与 PNG/JPEG 内容不匹配");
        }
    }

    // upload 和 validateContent 调用：确认 Markdown 为非空 UTF-8，供后续解析阶段安全读取。
    private static void validateMarkdown(Path file) throws IOException {
        try {
            if (Files.readString(file, StandardCharsets.UTF_8).isBlank()) {
                throw new IllegalArgumentException("Markdown 文件不能为空");
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Markdown 必须为 UTF-8 编码", exception);
        }
    }

    // upload 失败时调用：移除本次保存的文件；数据库插入由事务回滚。
    private void deleteSaved(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException ignored) {
                // 保留原始失败原因；残留文件不会因 UUID 键而被公开访问。
            }
        }
    }

    private record StoredEntry(String relativePath, String fileType, KnowledgeFileStorage.StoredFile file) { }

    public record Upload(UUID id, String originalFilename, String format, String originalStorageKey,
                         List<UploadFile> files) { }

    public record UploadFile(String relativePath, String fileType, String storageKey, long size, String sha256) { }
}
