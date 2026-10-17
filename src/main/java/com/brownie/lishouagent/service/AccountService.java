package com.brownie.lishouagent.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements UserDetailsService, ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords,
            @Value("${lishou.bootstrap.username:}") String bootstrapUsername,
            @Value("${lishou.bootstrap.password:}") String bootstrapPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.query("SELECT username, password_hash, status FROM app_user WHERE lower(username)=lower(?)",
                result -> {
                    if (!result.next() || result.getString("password_hash") == null) {
                        throw new UsernameNotFoundException("Unknown account");
                    }
                    return User.withUsername(result.getString("username"))
                            .password(result.getString("password_hash"))
                            .disabled(!"ACTIVE".equals(result.getString("status")))
                            .roles("USER").build();
                }, username);
    }

    public UUID idFor(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE lower(username)=lower(?) AND status='ACTIVE'",
                UUID.class, username);
    }

    // 登录用户创建同事账号；密码只以 BCrypt 摘要进入 app_user。
    public UUID create(String username, String displayName, String password) {
        if (username == null || username.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("账号和显示名称不能为空");
        }
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("密码至少需要 12 个字符");
        }
        return jdbc.queryForObject("INSERT INTO app_user(username,display_name,password_hash) "
                        + "VALUES (?,?,?) RETURNING id", UUID.class,
                username.trim(), displayName.trim(), passwords.encode(password));
    }

    // 仅显式配置两个环境变量且空库时初始化第一个账号；正常启动不会改动已有用户。
    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapUsername.isBlank() && bootstrapPassword.isBlank()) return;
        if (bootstrapUsername.isBlank() || bootstrapPassword.isBlank()) {
            throw new IllegalStateException("Bootstrap username and password must both be configured");
        }
        Integer users = jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
        if (users == 0) create(bootstrapUsername, bootstrapUsername, bootstrapPassword);
    }
}
