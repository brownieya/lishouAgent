package com.brownie.lishouagent.service;

import com.brownie.lishouagent.model.SourceReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ChatRepository implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ChatRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    // 重启后将上次进程未完成的流标为中断，避免历史记录一直显示“生成中”。
    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("UPDATE chat_message SET status='INTERRUPTED',updated_at=now(),completed_at=now() "
                + "WHERE status IN ('PENDING','GENERATING')");
    }

    // 会话操作都带用户 ID；数据库查询不会返回其他用户的会话。
    public List<SessionRow> sessions(UUID userId) {
        return jdbc.query("SELECT id,title,updated_at FROM chat_session WHERE user_id=? AND status='ACTIVE' "
                + "ORDER BY updated_at DESC,id DESC", (rs, row) ->
                new SessionRow((UUID) rs.getObject("id"), rs.getString("title"),
                        rs.getObject("updated_at", OffsetDateTime.class)), userId);
    }

    public SessionRow createSession(UUID userId) {
        UUID id = jdbc.queryForObject("INSERT INTO chat_session(user_id) VALUES (?) RETURNING id", UUID.class, userId);
        return session(userId, id);
    }

    public SessionRow session(UUID userId, UUID sessionId) {
        return jdbc.query("SELECT id,title,updated_at FROM chat_session "
                        + "WHERE id=? AND user_id=? AND status='ACTIVE'", rs -> {
                    if (!rs.next()) throw missingSession();
                    return new SessionRow((UUID) rs.getObject("id"), rs.getString("title"),
                            rs.getObject("updated_at", OffsetDateTime.class));
                }, sessionId, userId);
    }

    public SessionRow rename(UUID userId, UUID sessionId, String title) {
        int changed = jdbc.update("UPDATE chat_session SET title=?,updated_at=now() "
                + "WHERE id=? AND user_id=? AND status='ACTIVE'", title.trim(), sessionId, userId);
        if (changed == 0) throw missingSession();
        return session(userId, sessionId);
    }

    public void delete(UUID userId, UUID sessionId) {
        int changed = jdbc.update("UPDATE chat_session SET status='DELETED',deleted_at=now(),updated_at=now() "
                + "WHERE id=? AND user_id=? AND status='ACTIVE'", sessionId, userId);
        if (changed == 0) throw missingSession();
    }

    // 分页读取完整存档；引用取发送当时的快照，不依赖现存 Wiki 版本。
    public List<MessageRow> messages(UUID userId, UUID sessionId, int limit, long before) {
        session(userId, sessionId);
        List<MessageRow> rows = jdbc.query("SELECT id,sequence_no,role,content,status,created_at "
                        + "FROM chat_message WHERE session_id=? AND sequence_no<? "
                        + "ORDER BY sequence_no DESC LIMIT ?", this::messageRow, sessionId, before, limit);
        java.util.Collections.reverse(rows);
        return rows.stream().map(row -> new MessageRow(row.id(), row.sequenceNo(), row.role(), row.content(),
                row.status(), row.createdAt(), sources(row.id()))).toList();
    }

    public List<Message> modelHistory(UUID userId, UUID sessionId) {
        session(userId, sessionId);
        List<Message> rows = jdbc.query("SELECT role,content FROM chat_message WHERE session_id=? "
                        + "AND status='COMPLETE' AND role IN ('USER','ASSISTANT') "
                        + "ORDER BY sequence_no DESC LIMIT 20", (rs, row) ->
                "USER".equals(rs.getString("role")) ? new UserMessage(rs.getString("content"))
                        : new AssistantMessage(rs.getString("content")), sessionId);
        java.util.Collections.reverse(rows);
        return rows;
    }

    // 发送问题时锁定会话行，连续写入用户消息与待生成答案，避免并发序号冲突。
    public UUID startTurn(UUID userId, UUID sessionId, String question) {
        return transactions.execute(status -> {
            List<UUID> locked = jdbc.query("SELECT id FROM chat_session WHERE id=? AND user_id=? "
                    + "AND status='ACTIVE' FOR UPDATE", (rs, row) -> (UUID) rs.getObject(1), sessionId, userId);
            if (locked.isEmpty()) throw missingSession();
            Long next = jdbc.queryForObject("SELECT COALESCE(max(sequence_no),0)+1 FROM chat_message "
                    + "WHERE session_id=?", Long.class, sessionId);
            jdbc.update("INSERT INTO chat_message(session_id,sequence_no,role,content,status,completed_at) "
                    + "VALUES (?,?,'USER',?,'COMPLETE',now())", sessionId, next, question);
            UUID answerId = jdbc.queryForObject("INSERT INTO chat_message(session_id,sequence_no,role,status) "
                    + "VALUES (?,?,'ASSISTANT','GENERATING') RETURNING id", UUID.class, sessionId, next + 1);
            jdbc.update("UPDATE chat_session SET updated_at=now() WHERE id=?", sessionId);
            return answerId;
        });
    }

    // 模型成功后一次提交答案和引用快照，失败或取消时不把部分内容标记为完成。
    public void complete(UUID answerId, String answer, List<Document> documents) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("UPDATE chat_message SET content=?,status='COMPLETE',updated_at=now(),completed_at=now() "
                    + "WHERE id=? AND status='GENERATING'", answer, answerId);
            if (changed == 0) throw new IllegalStateException("Answer is no longer generating");
            for (int index = 0; index < documents.size(); index++) {
                Document document = documents.get(index);
                SourceReference source = SourceReference.from(document);
                jdbc.update("INSERT INTO chat_message_source(message_id,source_no,title_snapshot,section_snapshot,"
                                + "source_url_snapshot,content_snapshot,metadata_snapshot) "
                                + "VALUES (?,?,?,?,?,?,jsonb_build_object('sourcePath',?,'updatedAt',?))",
                        answerId, index + 1, source.title().isBlank() ? "未命名文档" : source.title(),
                        source.section(), document.getMetadata().get("sourceUrl"), document.getText(),
                        source.sourcePath(), source.updatedAt());
            }
        });
    }

    public void stop(UUID answerId, String partial, String state) {
        jdbc.update("UPDATE chat_message SET content=?,status=?,updated_at=now(),completed_at=now() "
                + "WHERE id=? AND status='GENERATING'", partial, state, answerId);
    }

    private MessageRow messageRow(ResultSet rs, int row) throws SQLException {
        return new MessageRow((UUID) rs.getObject("id"), rs.getLong("sequence_no"),
                rs.getString("role"), rs.getString("content"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), List.of());
    }

    private List<SourceReference> sources(UUID messageId) {
        return jdbc.query("SELECT source_no,title_snapshot,section_snapshot,"
                        + "metadata_snapshot->>'sourcePath' AS source_path,"
                        + "metadata_snapshot->>'updatedAt' AS updated_at "
                        + "FROM chat_message_source WHERE message_id=? ORDER BY source_no", (rs, row) ->
                new SourceReference(String.valueOf(rs.getInt("source_no")), rs.getString("title_snapshot"),
                        rs.getString("section_snapshot"), rs.getString("source_path"),
                        rs.getString("updated_at")), messageId);
    }

    private static ResponseStatusException missingSession() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
    }

    public record SessionRow(UUID id, String title, OffsetDateTime updatedAt) { }
    public record MessageRow(UUID id, long sequenceNo, String role, String content, String status,
            OffsetDateTime createdAt, List<SourceReference> sources) { }
}
