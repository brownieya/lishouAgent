package com.brownie.lishouagent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class IngestTaskRecovery implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public IngestTaskRecovery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 后端启动时只修正遗留任务状态，不读取 Wiki，也不自动发起 Embedding。
    @Override
    public void run(ApplicationArguments arguments) {
        jdbc.update("UPDATE public.ingest_task SET status='INTERRUPTED',error_code='PROCESS_RESTARTED',"
                + "error_message='服务重启，任务已中断，可手动重试',finished_at=now(),updated_at=now() "
                + "WHERE status IN ('PENDING','RUNNING')");
    }
}
