package com.brownie.lishouagent.service;

import com.brownie.lishouagent.model.ImportResult;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeImportService {
    private static final int EMBEDDING_BATCH_SIZE = 10;
    private final LocalWikiDocumentLoader loader;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeImportService(LocalWikiDocumentLoader loader, VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.loader = loader;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 同步导入入口：沿用同一事务替换向量，失败时恢复旧内容。 */
    @Transactional
    public ImportResult importLocal() {
        return importLocal((total, completed) -> { });
    }

    /** 后台任务调用：每批完成后报告处理数；向量写入仍在同一事务中，失败时整体回滚。 */
    @Transactional
    public ImportResult importLocal(BiConsumer<Integer, Integer> progress) {
        List<Document> chunks = loader.load();
        progress.accept(chunks.size(), 0);
        var documentIds = chunks.stream().map(chunk -> chunk.getMetadata().get("documentId").toString())
                .collect(Collectors.toSet());
        for (String documentId : documentIds) {
            jdbcTemplate.update("DELETE FROM public.vector_store WHERE metadata ->> 'documentId' = ?", documentId);
        }
        for (int offset = 0; offset < chunks.size(); offset += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(offset + EMBEDDING_BATCH_SIZE, chunks.size());
            vectorStore.add(chunks.subList(offset, end));
            progress.accept(chunks.size(), end);
        }
        return new ImportResult(documentIds.size(), chunks.size(), chunks.size());
    }
}
