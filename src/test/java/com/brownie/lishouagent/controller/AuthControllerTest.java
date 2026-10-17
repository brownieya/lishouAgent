package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.config.SecurityConfig;
import com.brownie.lishouagent.service.AccountService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountService accounts;

    @Test
    void accountListStillRequiresLogin() throws Exception {
        mvc.perform(get("/api/auth/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts);
    }

    @Test
    void disabledAccountCannotKeepUsingAnExistingSession() throws Exception {
        when(accounts.isActive("alice")).thenReturn(false);

        mvc.perform(get("/api/auth/users").session(sessionFor("alice")))
                .andExpect(status().isUnauthorized());
        verify(accounts).isActive("alice");
    }

    @Test
    void loggedInUserCanListAccountsWithoutSeeingPasswordHashes() throws Exception {
        UUID id = UUID.randomUUID();
        when(accounts.isActive("alice")).thenReturn(true);
        when(accounts.list()).thenReturn(List.of(new AccountService.Account(id, "alice", "Alice", "ACTIVE")));

        mvc.perform(get("/api/auth/users").session(sessionFor("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    private static MockHttpSession sessionFor(String username) {
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "", List.of(
                new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
