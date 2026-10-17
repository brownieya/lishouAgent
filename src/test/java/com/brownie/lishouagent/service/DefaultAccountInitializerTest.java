package com.brownie.lishouagent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccountInitializerTest {
    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private AccountService accounts;

    @Test
    void emptyDatabaseGetsOneDefaultAdmin() {
        when(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).thenReturn(0);

        new DefaultAccountInitializer(jdbc, accounts).run(null);

        verify(jdbc).execute("LOCK TABLE app_user IN EXCLUSIVE MODE");
        verify(accounts).create("admin", "管理员", "123456");
    }

    @Test
    void existingAccountsAreNeverOverwritten() {
        when(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).thenReturn(1);

        new DefaultAccountInitializer(jdbc, accounts).run(null);

        verifyNoInteractions(accounts);
    }
}
