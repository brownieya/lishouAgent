package com.brownie.lishouagent.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeFileStorageTest {
    @TempDir
    Path directory;

    @Test
    void sameOriginalNameGetsSeparateFilesAndStableDigest() throws Exception {
        var storage = new KnowledgeFileStorage(directory.toString());
        byte[] content = "公司资料".getBytes(StandardCharsets.UTF_8);

        var first = storage.store("wiki.md", new ByteArrayInputStream(content));
        var second = storage.store("wiki.md", new ByteArrayInputStream(content));

        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(first.size()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(storage.pathFor(first.storageKey()))).isEqualTo(content);
        assertThat(Files.readAllBytes(storage.pathFor(second.storageKey()))).isEqualTo(content);
    }

    @Test
    void rejectsClientPathsAndRemovesOversizedPartialFile() throws Exception {
        var storage = new KnowledgeFileStorage(directory.toString());
        assertThatThrownBy(() -> storage.store("../secret.md", new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.pathFor("../secret.md"))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] oversized = new byte[30 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> storage.store("large.md", new ByteArrayInputStream(oversized)))
                .isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }
}
