package com.brownie.lishouagent.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeFileStorage {
    private static final long MAX_FILE_BYTES = 30L * 1024 * 1024;
    private final Path directory;

    public KnowledgeFileStorage(@Value("${lishou.knowledge.storage-dir:./data/wiki/storage}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    // 上传入口调用：以随机存储键保存原文件，原始文件名只作为元数据，避免同名覆盖和路径穿越。
    public StoredFile store(String originalName, InputStream input) throws IOException {
        if (originalName == null || originalName.isBlank() || originalName.length() > 500
                || originalName.contains("/") || originalName.contains("\\") || originalName.contains("..")) {
            throw new IllegalArgumentException("文件名不正确");
        }
        Files.createDirectories(directory);
        Path root = directory.toRealPath();
        String key = UUID.randomUUID().toString();
        Path file = root.resolve(key);
        MessageDigest digest = sha256();
        long size = 0;
        try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > MAX_FILE_BYTES) throw new IllegalArgumentException("文件不能超过 30MB");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(file);
            throw exception;
        }
        return new StoredFile(key, originalName, size, HexFormat.of().formatHex(digest.digest()));
    }

    // 原文预览或解析时调用：只接受服务生成的 UUID 存储键，不接受客户端提供的路径。
    public Path pathFor(String key) {
        UUID.fromString(key);
        Path file = directory.resolve(key).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return file;
    }

    // 上传流程回滚时调用：仅删除服务生成的 UUID 文件，不接受客户端路径。
    public void delete(String key) throws IOException {
        UUID.fromString(key);
        Files.deleteIfExists(directory.resolve(key));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record StoredFile(String storageKey, String originalName, long size, String sha256) { }
}
