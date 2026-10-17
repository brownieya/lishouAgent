package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.AccountService;
import com.brownie.lishouagent.service.ChatRepository;
import com.brownie.lishouagent.service.ChatRepository.MessageRow;
import com.brownie.lishouagent.service.ChatRepository.SessionRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {
    private final ChatRepository chats;
    private final AccountService accounts;

    public ChatSessionController(ChatRepository chats, AccountService accounts) {
        this.chats = chats;
        this.accounts = accounts;
    }

    @GetMapping
    public List<SessionRow> list(Principal user) {
        return chats.sessions(accounts.idFor(user.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionRow create(Principal user) {
        return chats.createSession(accounts.idFor(user.getName()));
    }

    @PatchMapping("/{id}")
    public SessionRow rename(Principal user, @PathVariable UUID id, @Valid @RequestBody RenameRequest body) {
        return chats.rename(accounts.idFor(user.getName()), id, body.title());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal user, @PathVariable UUID id) {
        chats.delete(accounts.idFor(user.getName()), id);
    }

    @GetMapping("/{id}/messages")
    public List<MessageRow> messages(Principal user, @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        if (limit < 1 || limit > 100 || before < 1) throw new IllegalArgumentException("Invalid page");
        return chats.messages(accounts.idFor(user.getName()), id, limit, before);
    }

    public record RenameRequest(@NotBlank @Size(max = 200) String title) { }
}
