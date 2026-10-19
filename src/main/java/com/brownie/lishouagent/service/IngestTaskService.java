package com.brownie.lishouagent.service;

import com.brownie.lishouagent.model.ImportResult;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestTaskService {
    private final JdbcTemplate jdbc;
    private final KnowledgeImportService importer;
    private final TaskExecutor executor;

    public IngestTaskService(JdbcTemplate jdbc, KnowledgeImportService importer,
            @Qualifier("ingestTaskExecutor") TaskExecutor executor) {
        this.jdbc = jdbc;
        this.importer = importer;
        this.executor = executor;
    }

    // 用户显式请求本地导入时调用：先持久化任务，再由后台线程执行已有的导入服务。
    public Task startLocalImport(UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.ingest_task(id,task_type,created_by,request_data) "
                + "VALUES (?,'IMPORT',?,'{\"source\":\"LOCAL\"}'::jsonb)", id, createdBy);
        schedule(id);
        return get(id);
    }

    public Task get(UUID id) {
        List<Task> tasks = jdbc.query("SELECT id,task_type,status,stage,total_items,completed_items,attempt_no,"
                        + "error_code,error_message FROM public.ingest_task WHERE id=?",
                (row, index) -> new Task(row.getObject("id", UUID.class), row.getString("task_type"),
                        row.getString("status"), row.getString("stage"), row.getInt("total_items"),
                        row.getInt("completed_items"), row.getInt("attempt_no"),
                        row.getString("error_code"), row.getString("error_message")), id);
        if (tasks.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入任务不存在");
        return tasks.getFirst();
    }

    // 用户手动重试失败或中断的本地导入；应用启动时不自动重新发送 Wiki 文本。
    public Task retryLocalImport(UUID id) {
        int changed = jdbc.update("UPDATE public.ingest_task SET status='PENDING',stage='QUEUED',"
                        + "total_items=0,completed_items=0,attempt_no=attempt_no+1,error_code=NULL,error_message=NULL,"
                        + "started_at=NULL,finished_at=NULL,updated_at=now() "
                        + "WHERE id=? AND task_type='IMPORT' AND request_data ->> 'source' = 'LOCAL' "
                        + "AND status IN ('FAILED','INTERRUPTED')", id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务不能重试");
        schedule(id);
        return get(id);
    }

    // startLocalImport/retryLocalImport 的辅助方法：提交后台工作；队列满时将任务标为失败，避免永久待处理。
    private void schedule(UUID id) {
        try {
            executor.execute(() -> runLocalImport(id));
        } catch (RuntimeException exception) {
            fail(id, "TASK_QUEUE_FULL", "导入队列暂不可用");
        }
    }

    // 后台线程调用：状态先变为 RUNNING；导入事务完成后才写成功，异常仅记录通用错误信息。
    private void runLocalImport(UUID id) {
        int claimed = jdbc.update("UPDATE public.ingest_task SET status='RUNNING',stage='EMBEDDING',"
                        + "started_at=now(),updated_at=now() WHERE id=? AND status='PENDING'", id);
        if (claimed == 0) return;
        try {
            ImportResult result = importer.importLocal();
            jdbc.update("UPDATE public.ingest_task SET status='COMPLETE',stage='FINISHED',"
                            + "total_items=?,completed_items=?,finished_at=now(),updated_at=now() WHERE id=?",
                    result.chunkCount(), result.importedCount(), id);
        } catch (Exception exception) {
            fail(id, "IMPORT_FAILED", "导入失败，请检查知识目录或模型配置");
        }
    }

    // runLocalImport/schedule 共用：只写固定错误码和提示，不把私有文档或模型异常正文存入数据库。
    private void fail(UUID id, String code, String message) {
        jdbc.update("UPDATE public.ingest_task SET status='FAILED',stage='FINISHED',"
                + "error_code=?,error_message=?,finished_at=now(),updated_at=now() WHERE id=?", code, message, id);
    }

    public record Task(UUID id, String taskType, String status, String stage, int totalItems,
                       int completedItems, int attemptNo, String errorCode, String errorMessage) { }
}
