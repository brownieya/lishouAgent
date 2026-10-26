package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.CompanyAssistantService;
import com.brownie.lishouagent.service.AccountService;
import com.brownie.lishouagent.service.ChatRepository;
import com.brownie.lishouagent.service.KnowledgeImportService;
import com.brownie.lishouagent.service.KnowledgeDocumentParseService;
import com.brownie.lishouagent.service.IngestTaskService;
import com.brownie.lishouagent.service.KnowledgeUploadService;
import com.brownie.lishouagent.model.ImportResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest({ChatController.class, KnowledgeController.class, HealthController.class})
@AutoConfigureMockMvc(addFilters = false)
class ApiControllerTest {
    private static final String CHAT_ID = "8598e45b-09a0-42de-b6c4-c140739a18f8";
    private static final UUID USER_ID = UUID.fromString("7598e45b-09a0-42de-b6c4-c140739a18f8");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CompanyAssistantService assistant;

    @MockitoBean
    private KnowledgeImportService importer;

    @MockitoBean
    private IngestTaskService tasks;

    @MockitoBean
    private AccountService accounts;

    @MockitoBean
    private ChatRepository chats;

    @MockitoBean
    private KnowledgeUploadService uploads;

    @MockitoBean
    private KnowledgeDocumentParseService parser;

    @Test
    void healthIdentifiesTheMigratedApplication() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("lishou-agent"));
        verifyNoInteractions(assistant, importer);
    }

    @Test
    void invalidInputNeverReachesTheModel() throws Exception {
        for (String body : new String[]{
                "{\"message\":\" \" ,\"chatId\":\"" + CHAT_ID + "\"}",
                "{\"message\":\"介绍公司\",\"chatId\":\"../other-user\"}",
                "{\"message\":\"" + "a".repeat(4001) + "\",\"chatId\":\"" + CHAT_ID + "\"}",
                "{\"message\":"}) {
            mvc.perform(post("/api/ai/chat/stream")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString());
        }
        verifyNoInteractions(assistant, importer);
    }

    @Test
    void chatEmitsNamedSseEvents() throws Exception {
        when(accounts.idFor("test-user")).thenReturn(USER_ID);
        when(assistant.stream("公司有哪些项目？", USER_ID, UUID.fromString(CHAT_ID))).thenReturn(Flux.just(
                event("sources", "[]"), event("answer", "现有资料不足。"), event("done", "{}")));

        var result = mvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(() -> "test-user")
                        .content("{\"message\":\"公司有哪些项目？\",\"chatId\":\"" + CHAT_ID + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:sources")))
                .andExpect(content().string(containsString("event:answer")))
                .andExpect(content().string(containsString("event:done")));
        verify(assistant).stream("公司有哪些项目？", USER_ID, UUID.fromString(CHAT_ID));
    }

    @Test
    void importReportsDocumentsAndChunks() throws Exception {
        when(importer.importLocal()).thenReturn(new ImportResult(2, 8, 8));
        mvc.perform(post("/api/knowledge/import/local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(2))
                .andExpect(jsonPath("$.chunkCount").value(8))
                .andExpect(jsonPath("$.importedCount").value(8));
        verify(importer).importLocal();
    }

    @Test
    void asynchronousImportReturnsTaskWithoutRunningSynchronousEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        when(accounts.idFor("test-user")).thenReturn(USER_ID);
        when(tasks.startLocalImport(USER_ID)).thenReturn(new IngestTaskService.Task(id, "IMPORT", "PENDING",
                "QUEUED", 0, 0, 1, null, null));

        mvc.perform(post("/api/knowledge/import/local/tasks").principal(() -> "test-user"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(tasks).startLocalImport(USER_ID);
        verifyNoInteractions(importer);
    }

    @Test
    void importErrorDoesNotEchoProviderSecrets() throws Exception {
        when(importer.importLocal()).thenThrow(new IllegalStateException("provider failed with secret-value"));
        mvc.perform(post("/api/knowledge/import/local"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("操作失败，请检查数据库、模型配置和知识目录后重试。"));
        verify(importer).importLocal();
    }

    @Test
    void uploadReturnsStoredManifestWithoutStartingImport() throws Exception {
        UUID id = UUID.randomUUID();
        var file = new MockMultipartFile("file", "guide.md", "text/markdown",
                "# 使用说明".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(accounts.idFor("test-user")).thenReturn(USER_ID);
        when(uploads.upload(file, USER_ID)).thenReturn(new KnowledgeUploadService.Upload(id, "guide.md", "MARKDOWN",
                UUID.randomUUID().toString(), java.util.List.of()));

        mvc.perform(multipart("/api/knowledge/uploads").file(file).principal(() -> "test-user"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.format").value("MARKDOWN"));
        verify(uploads).upload(file, USER_ID);
        verifyNoInteractions(importer, tasks);
    }

    @Test
    void parsedUploadReturnsStructureWithoutStartingImport() throws Exception {
        UUID id = UUID.randomUUID();
        when(parser.parse(id)).thenReturn(new KnowledgeDocumentParseService.ParseResult(id,
                java.util.List.of(new KnowledgeDocumentParseService.ParsedDocument("guide.md", "说明",
                        java.util.List.of(new KnowledgeDocumentParseService.Block("HEADING", "# 说明",
                                java.util.List.of("说明"), 1, 1)), java.util.List.of())), java.util.List.of()));

        mvc.perform(get("/api/knowledge/uploads/{id}/parsed", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].title").value("说明"))
                .andExpect(jsonPath("$.documents[0].blocks[0].type").value("HEADING"));
        verify(parser).parse(id);
        verifyNoInteractions(importer, tasks);
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
