package com.brownie.lishouagent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式启用的本地数据库检查；事务回滚，不保留测试账号、会话或消息。 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "LISHOU_DB_INTEGRATION", matches = "true")
class ChatRepositoryDatabaseTest {
    @Autowired
    private AccountService accounts;

    @Autowired
    private ChatRepository chats;

    @Test
    void twoUsersCannotReadEachOthersMessagesAndCompletedAnswerKeepsSources() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = accounts.create("test_alice_" + suffix, "Alice", "temporary-strong-password");
        UUID bob = accounts.create("test_bob_" + suffix, "Bob", "temporary-strong-password");
        UUID session = chats.createSession(alice).id();
        UUID answer = chats.startTurn(alice, session, "测试问题");
        Document source = Document.builder().text("测试资料").metadata(Map.of(
                "title", "测试标题", "section", "测试章节", "sourcePath", "test.md")).build();
        chats.complete(answer, "测试回答", List.of(source));

        assertThat(chats.messages(alice, session, 50, Long.MAX_VALUE)).hasSize(2);
        assertThat(chats.messages(alice, session, 50, Long.MAX_VALUE).get(1).sources())
                .extracting(reference -> reference.title()).containsExactly("测试标题");
        assertThat(chats.messages(alice, session, 50, Long.MAX_VALUE).get(1).sources())
                .extracting(reference -> reference.sourcePath()).containsExactly("test.md");
        assertThat(chats.modelHistory(alice, session)).extracting(message -> message.getText())
                .containsExactly("测试问题", "测试回答");
        assertThat(chats.sessions(bob)).isEmpty();
        assertThatThrownBy(() -> chats.messages(bob, session, 50, Long.MAX_VALUE))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> chats.startTurn(bob, session, "越权提问"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
