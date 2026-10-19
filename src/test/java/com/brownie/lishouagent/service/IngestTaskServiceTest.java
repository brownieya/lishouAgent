package com.brownie.lishouagent.service;

import com.brownie.lishouagent.model.ImportResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestTaskServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeImportService importer;

    @Test
    @SuppressWarnings("unchecked")
    void retryRunsOnlyAfterUserRequestAndRecordsCompletedCount() {
        UUID id = UUID.randomUUID();
        TaskExecutor immediate = Runnable::run;
        var tasks = new IngestTaskService(jdbc, importer, immediate);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='PENDING'"), eq(id))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='RUNNING'"), eq(id))).thenReturn(1);
        when(importer.importLocal()).thenReturn(new ImportResult(2, 5, 5));
        when(jdbc.query(startsWith("SELECT id,task_type"), any(RowMapper.class), eq(id)))
                .thenReturn(List.of(new IngestTaskService.Task(id, "IMPORT", "COMPLETE", "FINISHED",
                        5, 5, 2, null, null)));

        var result = tasks.retryLocalImport(id);

        assertThatTaskComplete(result);
        verify(importer).importLocal();
        verify(jdbc).update(startsWith("UPDATE public.ingest_task SET status='COMPLETE'"), eq(5), eq(5), eq(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureStoresGenericMessageRatherThanProviderException() {
        UUID id = UUID.randomUUID();
        var tasks = new IngestTaskService(jdbc, importer, Runnable::run);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='PENDING'"), eq(id))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='RUNNING'"), eq(id))).thenReturn(1);
        when(importer.importLocal()).thenThrow(new IllegalStateException("private Wiki text and provider key"));
        when(jdbc.query(startsWith("SELECT id,task_type"), any(RowMapper.class), eq(id)))
                .thenReturn(List.of(new IngestTaskService.Task(id, "IMPORT", "FAILED", "FINISHED",
                        0, 0, 2, "IMPORT_FAILED", "导入失败，请检查知识目录或模型配置")));

        var result = tasks.retryLocalImport(id);

        org.assertj.core.api.Assertions.assertThat(result.errorMessage()).doesNotContain("private Wiki");
        verify(jdbc).update(startsWith("UPDATE public.ingest_task SET status='FAILED'"),
                eq("IMPORT_FAILED"), eq("导入失败，请检查知识目录或模型配置"), eq(id));
    }

    private static void assertThatTaskComplete(IngestTaskService.Task task) {
        org.assertj.core.api.Assertions.assertThat(task.status()).isEqualTo("COMPLETE");
        org.assertj.core.api.Assertions.assertThat(task.completedItems()).isEqualTo(5);
    }
}
