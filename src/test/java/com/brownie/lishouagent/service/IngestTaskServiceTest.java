package com.brownie.lishouagent.service;

import com.brownie.lishouagent.model.ImportResult;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
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
    @Mock IngestTaskProgressService progress;

    @Test
    @SuppressWarnings("unchecked")
    void retryRunsOnlyAfterUserRequestAndRecordsCompletedCount() {
        UUID id = UUID.randomUUID();
        TaskExecutor immediate = Runnable::run;
        var tasks = new IngestTaskService(jdbc, importer, progress, immediate);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='PENDING'"), eq(id))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='RUNNING'"), eq(id))).thenReturn(1);
        when(importer.importLocal(any())).thenReturn(new ImportResult(2, 5, 5));
        when(jdbc.query(startsWith("SELECT id,task_type"), any(RowMapper.class), eq(id)))
                .thenReturn(List.of(new IngestTaskService.Task(id, "IMPORT", "COMPLETE", "FINISHED",
                        5, 5, 2, null, null)));

        var result = tasks.retryLocalImport(id);

        assertThatTaskComplete(result);
        verify(importer).importLocal(any());
        verify(jdbc).update(startsWith("UPDATE public.ingest_task SET status='COMPLETE'"), eq(5), eq(5), eq(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureStoresGenericMessageRatherThanProviderException() {
        UUID id = UUID.randomUUID();
        var tasks = new IngestTaskService(jdbc, importer, progress, Runnable::run);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='PENDING'"), eq(id))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='RUNNING'"), eq(id))).thenReturn(1);
        when(importer.importLocal(any())).thenThrow(new IllegalStateException("private Wiki text and provider key"));
        when(jdbc.query(startsWith("SELECT id,task_type"), any(RowMapper.class), eq(id)))
                .thenReturn(List.of(new IngestTaskService.Task(id, "IMPORT", "FAILED", "FINISHED",
                        0, 0, 2, "IMPORT_FAILED", "导入失败，请检查知识目录或模型配置")));

        var result = tasks.retryLocalImport(id);

        org.assertj.core.api.Assertions.assertThat(result.errorMessage()).doesNotContain("private Wiki");
        verify(jdbc).update(startsWith("UPDATE public.ingest_task SET status='FAILED'"),
                eq("IMPORT_FAILED"), eq("导入失败，请检查知识目录或模型配置"), eq(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchProgressIsForwardedBeforeTaskCompletes() {
        UUID id = UUID.randomUUID();
        var tasks = new IngestTaskService(jdbc, importer, progress, Runnable::run);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='PENDING'"), eq(id))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE public.ingest_task SET status='RUNNING'"), eq(id))).thenReturn(1);
        when(importer.importLocal(any())).thenAnswer(invocation -> {
            BiConsumer<Integer, Integer> report = invocation.getArgument(0);
            report.accept(23, 0);
            report.accept(23, 10);
            report.accept(23, 23);
            return new ImportResult(2, 23, 23);
        });
        when(jdbc.query(startsWith("SELECT id,task_type"), any(RowMapper.class), eq(id)))
                .thenReturn(List.of(new IngestTaskService.Task(id, "IMPORT", "COMPLETE", "FINISHED",
                        23, 23, 2, null, null)));

        tasks.retryLocalImport(id);

        var order = org.mockito.Mockito.inOrder(progress, jdbc);
        order.verify(progress).record(id, 23, 0);
        order.verify(progress).record(id, 23, 10);
        order.verify(progress).record(id, 23, 23);
        order.verify(jdbc).update(startsWith("UPDATE public.ingest_task SET status='COMPLETE'"),
                eq(23), eq(23), eq(id));
    }

    private static void assertThatTaskComplete(IngestTaskService.Task task) {
        org.assertj.core.api.Assertions.assertThat(task.status()).isEqualTo("COMPLETE");
        org.assertj.core.api.Assertions.assertThat(task.completedItems()).isEqualTo(5);
    }
}
