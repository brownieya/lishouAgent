package com.brownie.lishouagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestTaskRecoveryTest {
    @Test
    void startupOnlyMarksOldPendingAndRunningTasksInterrupted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new IngestTaskRecovery(jdbc).run(null);
        verify(jdbc).update(contains("WHERE status IN ('PENDING','RUNNING')"));
    }
}
