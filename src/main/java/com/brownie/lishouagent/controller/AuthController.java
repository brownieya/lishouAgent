package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager manager;
    private final AccountService accounts;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager manager, AccountService accounts) {
        this.manager = manager;
        this.accounts = accounts;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    // 登录后将认证身份写入服务器会话，后续接口从会话获得用户名。
    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        var authentication = manager.authenticate(
                new UsernamePasswordAuthenticationToken(body.username(), body.password()));
        request.getSession(true);
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return current(authentication);
    }

    @GetMapping("/me")
    public UserResponse current(Principal principal) {
        return new UserResponse(accounts.idFor(principal.getName()), principal.getName());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest body) {
        UUID id = accounts.create(body.username(), body.displayName(), body.password());
        return new UserResponse(id, body.username().trim());
    }

    @GetMapping("/users")
    public List<AccountService.Account> users() {
        return accounts.list();
    }

    @GetMapping("/users/{id}")
    public AccountService.Account user(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @PatchMapping("/users/{id}")
    public AccountService.Account updateUser(@PathVariable UUID id, @RequestBody UpdateUserRequest body,
            Principal principal) {
        return accounts.updateSelf(accounts.idFor(principal.getName()), id, body.displayName(), body.password());
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID id, Principal principal, HttpServletRequest request) {
        accounts.disableSelf(accounts.idFor(principal.getName()), id);
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    public record LoginRequest(@NotBlank String username, @NotEmpty String password) { }
    public record CreateUserRequest(@NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 160) String displayName, @NotEmpty String password) { }
    public record UpdateUserRequest(String displayName, String password) { }
    public record UserResponse(UUID id, String username) { }
}
