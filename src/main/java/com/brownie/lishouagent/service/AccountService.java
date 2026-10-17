package com.brownie.lishouagent.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
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

    public boolean isActive(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE lower(username)=lower(?) AND status='ACTIVE')",
                Boolean.class, username));
    }

    // 登录用户创建同事账号；密码只以 BCrypt 摘要进入 app_user。
    public UUID create(String username, String displayName, String password) {
        if (username == null || username.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("账号和显示名称不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return jdbc.queryForObject("INSERT INTO app_user(username,display_name,password_hash) "
                        + "VALUES (?,?,?) RETURNING id", UUID.class,
                username.trim(), displayName.trim(), passwords.encode(password));
    }

    public List<Account> list() {
        return jdbc.query("SELECT id,username,display_name,status FROM app_user ORDER BY created_at,id",
                (result, row) -> new Account(result.getObject("id", UUID.class), result.getString("username"),
                        result.getString("display_name"), result.getString("status")));
    }

    public Account get(UUID id) {
        return jdbc.query("SELECT id,username,display_name,status FROM app_user WHERE id=?", result -> {
            if (!result.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
            return new Account(result.getObject("id", UUID.class), result.getString("username"),
                    result.getString("display_name"), result.getString("status"));
        }, id);
    }

    // 当前只允许修改自己的显示名称和密码；用户名保持不变，避免旧会话失去身份关联。
    public Account updateSelf(UUID requester, UUID id, String displayName, String password) {
        requireSelf(requester, id);
        if (displayName == null && password == null) throw new IllegalArgumentException("请提供要修改的字段");
        if (displayName != null && displayName.isBlank()) throw new IllegalArgumentException("显示名称不能为空");
        if (password != null && password.isEmpty()) throw new IllegalArgumentException("密码不能为空");
        int updated = jdbc.update("UPDATE app_user SET display_name=COALESCE(?,display_name), "
                        + "password_hash=COALESCE(?,password_hash),updated_at=now() WHERE id=? AND status='ACTIVE'",
                displayName == null ? null : displayName.trim(), password == null ? null : passwords.encode(password), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        return get(id);
    }

    // 停用而非物理删除，以保留该账号已有的会话和审计关联。
    @Transactional
    public void disableSelf(UUID requester, UUID id) {
        requireSelf(requester, id);
        jdbc.execute("LOCK TABLE app_user IN EXCLUSIVE MODE");
        Integer active = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE status='ACTIVE'", Integer.class);
        if (active != null && active <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用最后一个可登录账号");
        }
        int updated = jdbc.update("UPDATE app_user SET status='DISABLED',updated_at=now() "
                + "WHERE id=? AND status='ACTIVE'", id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
    }

    private void requireSelf(UUID requester, UUID id) {
        if (!requester.equals(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能修改自己的账号");
    }

    public record Account(UUID id, String username, String displayName, String status) { }
}
