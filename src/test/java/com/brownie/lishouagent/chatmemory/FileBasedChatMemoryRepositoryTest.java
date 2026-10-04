package com.brownie.lishouagent.chatmemory;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileBasedChatMemoryRepositoryTest {
    private static final String ID = "ff25d1c8-3567-4e24-9c62-f85d96a48059";
    @TempDir
    Path directory;

    @Test
    void conversationSurvivesRepositoryRecreationAndCanBeDeleted() {
        var original = new FileBasedChatMemoryRepository(directory.toString());
        original.saveAll(ID, List.of(new UserMessage("项目怎么部署？"), new AssistantMessage("参阅部署文档。")));

        var recreated = new FileBasedChatMemoryRepository(directory.toString());
        var messages = recreated.findByConversationId(ID);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("项目怎么部署？");
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("参阅部署文档。");
        assertThat(recreated.findConversationIds()).contains(ID);

        recreated.deleteByConversationId(ID);
        assertThat(original.findByConversationId(ID)).isEmpty();
        assertThat(recreated.findConversationIds()).doesNotContain(ID);
    }

    @Test
    void saveAllReplacesOldConversationInsteadOfAppendingDuplicates() {
        var repository = new FileBasedChatMemoryRepository(directory.toString());
        repository.saveAll(ID, List.of(new UserMessage("旧问题")));
        repository.saveAll(ID, List.of(new UserMessage("新问题")));
        assertThat(repository.findByConversationId(ID))
                .extracting(message -> message.getText()).containsExactly("新问题");
    }

    @Test
    void fileSystemPathsCannotBeUsedAsConversationIds() {
        var repository = new FileBasedChatMemoryRepository(directory.toString());
        for (String id : List.of("../outside", "..\\outside", "/outside", "D:\\outside", "")) {
            assertThatThrownBy(() -> repository.saveAll(id, List.of(new UserMessage("test"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.findByConversationId(id))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.deleteByConversationId(id))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
