package com.brownie.lishouagent;

import com.brownie.lishouagent.service.CompanyAssistantService;
import com.brownie.lishouagent.service.KnowledgeImportService;
import com.brownie.lishouagent.service.LocalWikiDocumentLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeVideoAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration"
})
class LishouAgentApplicationTest {
    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void productionComponentsCanBeAssembledWithoutImportingWikiOrCallingExternalServices() {
        assertThat(context.getBean(CompanyAssistantService.class)).isNotNull();
        assertThat(context.getBean(KnowledgeImportService.class)).isNotNull();
        assertThat(context.getBean(LocalWikiDocumentLoader.class)).isNotNull();
        assertThat(context.getBean(VectorStore.class)).isNotNull();
        verify(chatModel, never()).stream(any(Prompt.class));
        verify(chatModel, never()).call(any(Prompt.class));
        verifyNoInteractions(embeddingModel, jdbcTemplate);
    }
}
