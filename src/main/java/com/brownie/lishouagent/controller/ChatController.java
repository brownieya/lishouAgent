package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.CompanyAssistantService;
import com.brownie.lishouagent.service.AccountService;
import com.brownie.lishouagent.service.ChatRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai/chat")
public class ChatController {
    private final CompanyAssistantService assistant;
    private final AccountService accounts;
    private final ChatRepository chats;

    public ChatController(CompanyAssistantService assistant, AccountService accounts, ChatRepository chats) {
        this.assistant = assistant;
        this.accounts = accounts;
        this.chats = chats;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request, Principal user) {
        UUID userId = accounts.idFor(user.getName());
        UUID sessionId = UUID.fromString(request.chatId());
        chats.session(userId, sessionId);
        return assistant.stream(request.message(), userId, sessionId);
    }

    public record ChatRequest(
            @NotBlank @Size(max = 4000) String message,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            String chatId) {
    }
}
