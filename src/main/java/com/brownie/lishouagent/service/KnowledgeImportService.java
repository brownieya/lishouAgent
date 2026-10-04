package com.brownie.lishouagent.service;

import com.brownie.lishouagent.model.ImportResult;
import java.util.List;
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

    /** All writes share a transaction; failed embedding/write rolls back replacement of old vectors. */
    @Transactional
    public ImportResult importLocal() {
        List<Document> chunks = loader.load();
        var documentIds = chunks.stream().map(chunk -> chunk.getMetadata().get("documentId").toString())
                .collect(Collectors.toSet());
        for (String documentId : documentIds) {
            jdbcTemplate.update("DELETE FROM public.vector_store WHERE metadata ->> 'documentId' = ?", documentId);
        }
        for (int offset = 0; offset < chunks.size(); offset += EMBEDDING_BATCH_SIZE) {
            vectorStore.add(chunks.subList(offset, Math.min(offset + EMBEDDING_BATCH_SIZE, chunks.size())));
        }
        return new ImportResult(documentIds.size(), chunks.size(), chunks.size());
    }
}
