package com.brownie.lishouagent.service;

import com.brownie.lishouagent.chatmemory.FileBasedChatMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyAssistantServiceTest {
    private static final String CHAT_ID = "6ef01175-9ec2-42d0-ae9e-85ecdd1bf648";
    @TempDir
    Path directory;

    @Test
    void noKnowledgeEmitsExplicitCompletionWithoutCallingChatModel() {
        var model = model();
        var store = mock(VectorStore.class);
        var memory = new FileBasedChatMemoryRepository(directory);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var assistant = new CompanyAssistantService(model, store, memory, new ObjectMapper(), 5, .45);

        var events = assistant.stream("某项目如何部署？", CHAT_ID).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer", "done");
        assertThat(events.get(0).data()).isEqualTo("[]");
        assertThat(events.get(1).data()).contains("没有找到足够的相关资料");
        verify(model, never()).stream(any(Prompt.class));
        verify(model, never()).call(any(Prompt.class));
        assertThat(memory.findByConversationId(CHAT_ID)).hasSize(2);
    }

    @Test
    void streamsSourcesAndAnswerThenPersistsOnlyTheCompletedConversation() throws Exception {
        var model = model();
        var store = mock(VectorStore.class);
        var memory = new FileBasedChatMemoryRepository(directory);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("先提交申请。"), response("[1]")));
        var assistant = new CompanyAssistantService(model, store, memory, new ObjectMapper(), 4, .6);

        var events = assistant.stream("怎么部署？", CHAT_ID).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer", "answer", "done");
        var sources = new ObjectMapper().readTree(events.get(0).data());
        assertThat(sources.get(0).get("title").asText()).isEqualTo("发布流程");
        assertThat(sources.get(0).get("sourcePath").asText()).isEqualTo("项目/发布.md");
        assertThat(events.get(1).data() + events.get(2).data()).isEqualTo("先提交申请。[1]");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertThat(prompt.getValue().getInstructions().stream().map(message -> message.getText()).toList())
                .anySatisfy(text -> assertThat(text).contains("本轮知识资料", "必须先提交审批"));
        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(search.capture());
        assertThat(search.getValue().getTopK()).isEqualTo(4);
        assertThat(search.getValue().getSimilarityThreshold()).isEqualTo(.6);

        assertThat(new FileBasedChatMemoryRepository(directory).findByConversationId(CHAT_ID))
                .extracting(message -> message.getText()).containsExactly("怎么部署？", "先提交申请。[1]");
    }

    @Test
    void modelFailureEmitsErrorWithoutDoneAndDoesNotPersistPartialAnswer() {
        var model = model();
        var store = mock(VectorStore.class);
        var memory = new FileBasedChatMemoryRepository(directory);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(response("未完成的回答")), Flux.error(new IllegalStateException("credential-secret"))));
        var assistant = new CompanyAssistantService(model, store, memory, new ObjectMapper(), 5, .45);

        var events = assistant.stream("怎么部署？", CHAT_ID).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer", "error");
        assertThat(events.getLast().data()).doesNotContain("credential-secret");
        assertThat(memory.findByConversationId(CHAT_ID)).isEmpty();
    }

    @Test
    void cancelledStreamDoesNotSaveAnIncompleteConversation() {
        var model = model();
        var store = mock(VectorStore.class);
        var memory = new FileBasedChatMemoryRepository(directory);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("第一段回答")), Flux.never()));
        var assistant = new CompanyAssistantService(model, store, memory, new ObjectMapper(), 5, .45);

        var events = assistant.stream("怎么部署？", CHAT_ID).take(2).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer");
        assertThat(memory.findByConversationId(CHAT_ID)).isEmpty();
    }

    private static ChatModel model() {
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        return model;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Document document() {
        return Document.builder().id("acfbb697-d373-4500-b70c-13e49b6e6c32").text("发布前必须先提交审批。")
                .metadata(Map.of("title", "发布流程", "sourcePath", "项目/发布.md", "section", "部署", "updatedAt", "2026-10-04"))
                .build();
    }
}
