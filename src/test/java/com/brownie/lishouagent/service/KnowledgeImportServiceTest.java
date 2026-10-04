package com.brownie.lishouagent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeImportServiceTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void replacesOnlyImportedDocumentsAndEmbedsInBatchesOfAtMostTen() {
        var loader = mock(LocalWikiDocumentLoader.class);
        var vectorStore = mock(VectorStore.class);
        var jdbc = mock(JdbcTemplate.class);
        List<Document> chunks = new ArrayList<>();
        for (int index = 0; index < 23; index++) {
            chunks.add(Document.builder().id("chunk-" + index).text("文档片段" + index)
                    .metadata(Map.of("documentId", index < 12 ? "doc-a" : "doc-b")).build());
        }
        when(loader.load()).thenReturn(chunks);

        var result = new KnowledgeImportService(loader, vectorStore, jdbc).importLocal();
        assertThat(result.documentCount()).isEqualTo(2);
        assertThat(result.chunkCount()).isEqualTo(23);
        assertThat(result.importedCount()).isEqualTo(23);
        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStore, times(3)).add(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(10, 10, 3);
        assertThat(batches.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(chunks);
        verify(jdbc).update("DELETE FROM public.vector_store WHERE metadata ->> 'documentId' = ?", "doc-a");
        verify(jdbc).update("DELETE FROM public.vector_store WHERE metadata ->> 'documentId' = ?", "doc-b");
    }

    @Test
    void invalidSourceStopsBeforeDatabaseWrites() {
        var loader = mock(LocalWikiDocumentLoader.class);
        var vectorStore = mock(VectorStore.class);
        var jdbc = mock(JdbcTemplate.class);
        when(loader.load()).thenThrow(new IllegalStateException("source is missing"));
        assertThatThrownBy(() -> new KnowledgeImportService(loader, vectorStore, jdbc).importLocal())
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc, vectorStore);
    }

    @Test
    void embeddingFailureIsPropagatedRatherThanReportingImportSuccess() {
        var loader = mock(LocalWikiDocumentLoader.class);
        var vectorStore = mock(VectorStore.class);
        var jdbc = mock(JdbcTemplate.class);
        when(loader.load()).thenReturn(List.of(new Document("片段", Map.of("documentId", "doc-a"))));
        doThrow(new IllegalStateException("embedding failed")).when(vectorStore).add(anyList());
        assertThatThrownBy(() -> new KnowledgeImportService(loader, vectorStore, jdbc).importLocal())
                .isInstanceOf(IllegalStateException.class).hasMessage("embedding failed");
    }
}
