package com.brownie.lishouagent.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private PasswordEncoder passwords;

    @Test
    void newAccountAcceptsOneCharacterPasswordAndStoresOnlyItsHash() {
        AccountService accounts = new AccountService(jdbc, passwords);
        UUID id = UUID.randomUUID();
        when(passwords.encode("x")).thenReturn("hashed-password");
        when(jdbc.queryForObject("INSERT INTO app_user(username,display_name,password_hash) "
                + "VALUES (?,?,?) RETURNING id", UUID.class, "admin", "管理员", "hashed-password"))
                .thenReturn(id);

        assertThat(accounts.create("admin", "管理员", "x")).isEqualTo(id);
        verify(passwords).encode("x");
    }

    @Test
    void accountChangesCannotTargetAnotherUser() {
        AccountService accounts = new AccountService(jdbc, passwords);
        UUID current = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> accounts.updateSelf(current, other, "新名称", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> accounts.disableSelf(current, other))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoMoreInteractions(jdbc, passwords);
    }

    @Test
    void lastActiveAccountCannotBeDisabled() {
        AccountService accounts = new AccountService(jdbc, passwords);
        UUID current = UUID.randomUUID();
        when(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE status='ACTIVE'", Integer.class))
                .thenReturn(1);

        assertThatThrownBy(() -> accounts.disableSelf(current, current))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(jdbc).execute("LOCK TABLE app_user IN EXCLUSIVE MODE");
    }

    @Test
    void anAccountCanBeDisabledWhenAnotherActiveAccountRemains() {
        AccountService accounts = new AccountService(jdbc, passwords);
        UUID current = UUID.randomUUID();
        when(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE status='ACTIVE'", Integer.class))
                .thenReturn(2);
        when(jdbc.update("UPDATE app_user SET status='DISABLED',updated_at=now() "
                + "WHERE id=? AND status='ACTIVE'", current)).thenReturn(1);

        accounts.disableSelf(current, current);

        verify(jdbc).execute("LOCK TABLE app_user IN EXCLUSIVE MODE");
        verify(jdbc).update("UPDATE app_user SET status='DISABLED',updated_at=now() "
                + "WHERE id=? AND status='ACTIVE'", current);
    }
}
