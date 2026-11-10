package com.brownie.lishouagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyAssistantServiceTest {
    private static final UUID CHAT_ID = UUID.fromString("6ef01175-9ec2-42d0-ae9e-85ecdd1bf648");
    private static final UUID USER_ID = UUID.fromString("7ef01175-9ec2-42d0-ae9e-85ecdd1bf648");
    private static final UUID ANSWER_ID = UUID.fromString("8ef01175-9ec2-42d0-ae9e-85ecdd1bf648");

    @Test
    void noKnowledgeEmitsExplicitCompletionWithoutCallingChatModel() {
        var model = model();
        var store = mock(VectorStore.class);
        var chats = repository();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var assistant = new CompanyAssistantService(model, store, chats, new ObjectMapper(), 5, .45);

        var events = assistant.stream("某项目如何部署？", USER_ID, CHAT_ID).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer", "done");
        assertThat(events.get(0).data()).isEqualTo("[]");
        assertThat(events.get(1).data()).contains("没有找到足够的相关资料");
        verify(model, never()).stream(any(Prompt.class));
        verify(model, never()).call(any(Prompt.class));
        verify(chats).complete(ANSWER_ID, events.get(1).data(), List.of());
    }

    @Test
    void streamsSourcesAndAnswerThenPersistsOnlyTheCompletedConversation() throws Exception {
        var model = model();
        var store = mock(VectorStore.class);
        var chats = repository();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("先提交申请。"), response("[1]")));
        var assistant = new CompanyAssistantService(model, store, chats, new ObjectMapper(), 4, .6);

        var events = assistant.stream("怎么部署？", USER_ID, CHAT_ID).collectList().block(Duration.ofSeconds(10));
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

        verify(chats).complete(ANSWER_ID, "先提交申请。[1]", List.of(document()));
    }

    @Test
    void modelFailureEmitsErrorWithoutDoneAndDoesNotPersistPartialAnswer() {
        var model = model();
        var store = mock(VectorStore.class);
        var chats = repository();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(response("未完成的回答")), Flux.error(new IllegalStateException("credential-secret"))));
        var assistant = new CompanyAssistantService(model, store, chats, new ObjectMapper(), 5, .45);

        var events = assistant.stream("怎么部署？", USER_ID, CHAT_ID).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer", "error");
        assertThat(events.getLast().data()).doesNotContain("credential-secret");
        verify(chats).stop(ANSWER_ID, "未完成的回答", "FAILED");
    }

    @Test
    void cancelledStreamDoesNotSaveAnIncompleteConversation() {
        var model = model();
        var store = mock(VectorStore.class);
        var chats = repository();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document()));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("第一段回答")), Flux.never()));
        var assistant = new CompanyAssistantService(model, store, chats, new ObjectMapper(), 5, .45);

        var events = assistant.stream("怎么部署？", USER_ID, CHAT_ID).take(2).collectList().block(Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.event()).containsExactly("sources", "answer");
        verify(chats).stop(ANSWER_ID, "第一段回答", "INTERRUPTED");
    }

    @Test
    void interfaceFollowUpSearchesOnlyTheEndpointNamedInThePreviousQuestion() {
        var model = model();
        var store = mock(VectorStore.class);
        var chats = repository();
        when(chats.modelHistory(USER_ID, CHAT_ID)).thenReturn(List.of(
                new UserMessage("/api/v1/demo/order/receive 是哪个项目的？"),
                new AssistantMessage("订单下发接口。")));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var assistant = new CompanyAssistantService(model, store, chats, new ObjectMapper(), 5, .45);

        assistant.stream("这个接口的请求参数是什么？", USER_ID, CHAT_ID).collectList().block(Duration.ofSeconds(10));

        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(search.capture());
        assertThat(search.getValue().hasFilterExpression()).isTrue();
        assertThat(search.getValue().getTopK()).isEqualTo(20);
        assertThat(search.getValue().getSimilarityThreshold()).isZero();
        assertThat(search.getValue().getFilterExpression().toString())
                .contains("endpoint", "/api/v1/demo/order/receive");
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void interfaceParameterFollowUpPresentsFieldTableBeforeExample() {
        var model = model();
        var store = mock(VectorStore.class);
        var chats = repository();
        when(chats.modelHistory(USER_ID, CHAT_ID)).thenReturn(List.of(
                new UserMessage("/api/v1/demo/order/receive 是哪个项目的？"),
                new AssistantMessage("订单下发接口。")));
        var common = Document.builder().id(UUID.randomUUID().toString()).text("通用约定：长度单位 mm。")
                .metadata(Map.of("title", "示例接口", "sourcePath", "示例/接口.md", "section", "通用约定",
                        "chunkIndex", 0)).build();
        var header = interfaceChunk(2, "根节点为订单数组。");
        var fields = interfaceChunk(3, "| 字段名 | 必填 |\n| code | 是 |\n| name | 否 |");
        var example = interfaceChunk(4, "请求示例：code 和 name 都有值。");
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            return request.getFilterExpression().toString().contains("通用约定")
                    ? List.of(common) : List.of(example, header, fields);
        });
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("name 可选。[2]")));
        var assistant = new CompanyAssistantService(model, store, chats, new ObjectMapper(), 5, .45);

        assistant.stream("这个接口的请求参数是什么？", USER_ID, CHAT_ID)
                .collectList().block(Duration.ofSeconds(10));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        String instructions = prompt.getValue().getInstructions().stream()
                .map(message -> message.getText()).reduce("", (left, right) -> left + right);
        assertThat(instructions).contains("逐项以该列判断必填与可选", "字段表与通用约定冲突",
                "类型为 number 不等于正整数", "不得指定按哪种单位提交", "不自动规定非空字符串");
        assertThat(instructions.indexOf("通用约定：长度单位 mm"))
                .isLessThan(instructions.indexOf("根节点为订单数组"));
        assertThat(instructions.indexOf("根节点为订单数组"))
                .isLessThan(instructions.indexOf("| 字段名 | 必填 |"));
        assertThat(instructions.indexOf("| 字段名 | 必填 |"))
                .isLessThan(instructions.indexOf("请求示例："));
        ArgumentCaptor<SearchRequest> searches = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store, times(2)).similaritySearch(searches.capture());
        assertThat(searches.getAllValues().get(1).getFilterExpression().toString())
                .contains("sourcePath", "示例/接口.md", "section", "通用约定");
        verify(chats).complete(ANSWER_ID, "name 可选。[2]", List.of(common, header, fields, example));
    }

    private static ChatModel model() {
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        return model;
    }

    private static ChatRepository repository() {
        var chats = mock(ChatRepository.class);
        when(chats.modelHistory(USER_ID, CHAT_ID)).thenReturn(List.of());
        when(chats.startTurn(any(UUID.class), any(UUID.class), any(String.class))).thenReturn(ANSWER_ID);
        return chats;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Document document() {
        return Document.builder().id("acfbb697-d373-4500-b70c-13e49b6e6c32").text("发布前必须先提交审批。")
                .metadata(Map.of("title", "发布流程", "sourcePath", "项目/发布.md", "section", "部署", "updatedAt", "2026-10-04"))
                .build();
    }

    private static Document interfaceChunk(int index, String text) {
        return Document.builder().id(UUID.randomUUID().toString()).text(text)
                .metadata(Map.of("title", "示例接口", "sourcePath", "示例/接口.md", "section", "接口一",
                        "endpoint", "/api/v1/demo/order/receive", "chunkIndex", index))
                .build();
    }
}
