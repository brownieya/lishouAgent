package com.brownie.lishouagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Temporary M0 storage for the model's message window, not complete chat history. */
@Repository
public class FileBasedChatMemoryRepository implements ChatMemoryRepository {
    private static final String SUFFIX = ".kryo";
    private final Path directory;

    @Autowired
    public FileBasedChatMemoryRepository(@Value("${lishou.chat-memory.directory:./data/chat-memory}") String directory) {
        this(Path.of(directory));
    }

    public FileBasedChatMemoryRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static void validateConversationId(String conversationId) {
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("chatId must contain 1-100 letters, digits, underscores or hyphens");
        }
    }

    private static Kryo newKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        return kryo;
    }

    private Path file(String conversationId) {
        validateConversationId(conversationId);
        Path path = directory.resolve(conversationId + SUFFIX).normalize();
        if (!path.startsWith(directory) || Files.isSymbolicLink(directory) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Invalid chat memory path");
        }
        return path;
    }

    @Override
    public synchronized List<String> findConversationIds() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list chat memory", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized List<Message> findByConversationId(String conversationId) {
        Path path = file(conversationId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Input input = new Input(Files.newInputStream(path))) {
            return newKryo().readObject(input, ArrayList.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read chat memory", e);
        }
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        Path target = file(conversationId);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "memory-", ".tmp");
            try (Output output = new Output(Files.newOutputStream(temporary))) {
                newKryo().writeObject(output, new ArrayList<>(messages));
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot save chat memory", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        try {
            Files.deleteIfExists(file(conversationId));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete chat memory", e);
        }
    }
}
