package com.brownie.lishouagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class IngestTaskConfig {
    @Bean("ingestTaskExecutor")
    public ThreadPoolTaskExecutor ingestTaskExecutor() {
        // 单线程串行执行本地导入，避免同时替换同一批 Wiki 向量。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("wiki-ingest-");
        executor.initialize();
        return executor;
    }
}
