package com.brownie.lishouagent.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestTaskProgressService {
    private final JdbcTemplate jdbc;

    public IngestTaskProgressService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // KnowledgeImportService 每批回调时由 IngestTaskService 调用；独立提交进度，供导入事务运行期间查询。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID taskId, int total, int completed) {
        jdbc.update("UPDATE public.ingest_task SET stage='EMBEDDING',total_items=?,completed_items=?,"
                + "updated_at=now() WHERE id=? AND status='RUNNING'", total, completed, taskId);
    }
}
