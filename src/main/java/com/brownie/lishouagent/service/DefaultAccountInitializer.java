package com.brownie.lishouagent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class DefaultAccountInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;

    public DefaultAccountInitializer(JdbcTemplate jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    // 仅空库时创建默认账号；事务内表锁避免并发启动重复初始化。
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.execute("LOCK TABLE app_user IN EXCLUSIVE MODE");
        Integer users = jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
        if (Integer.valueOf(0).equals(users)) {
            accounts.create("admin", "管理员", "123456");
        }
    }
}
