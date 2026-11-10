package com.brownie.lishouagent.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestTaskProgressServiceTest {
    @Test
    void progressUsesSeparateTransactionAndOnlyUpdatesRunningTask() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        Transactional annotation = IngestTaskProgressService.class
                .getMethod("record", UUID.class, int.class, int.class).getAnnotation(Transactional.class);
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        new IngestTaskProgressService(jdbc).record(id, 23, 10);

        verify(jdbc).update(contains("WHERE id=? AND status='RUNNING'"), eq(23), eq(10), eq(id));
    }
}
