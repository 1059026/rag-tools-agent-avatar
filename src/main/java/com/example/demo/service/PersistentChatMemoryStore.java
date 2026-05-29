package com.example.demo.service;

import com.example.demo.mapper.ChatMessageHistoryMapper;
import com.example.demo.model.ChatMessageHistory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageHistoryMapper mapper;

    public PersistentChatMemoryStore(ChatMessageHistoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String chatId = Objects.toString(memoryId, null);
        if (chatId == null || chatId.isBlank()) {
            return new ArrayList<>();
        }

        List<ChatMessageHistory> rows = mapper.selectByChatId(chatId);
        List<ChatMessage> messages = new ArrayList<>(rows.size());
        for (ChatMessageHistory row : rows) {
            String json = row.getContent();
            if (json == null || json.isBlank()) {
                continue;
            }
            messages.add(ChatMessageDeserializer.messageFromJson(json));
        }
        return messages;
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String chatId = Objects.toString(memoryId, null);
        if (chatId == null || chatId.isBlank()) {
            return;
        }

        mapper.deleteByChatId(chatId);

        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<ChatMessageHistory> rows = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            ChatMessageHistory row = new ChatMessageHistory();
            row.setChatId(chatId);
            row.setRole(toDbRole(message));
            row.setContent(ChatMessageSerializer.messageToJson(message));
            rows.add(row);
        }

        if (!rows.isEmpty()) {
            mapper.insertBatch(rows);
        }
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        String chatId = Objects.toString(memoryId, null);
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        mapper.deleteByChatId(chatId);
    }

    private static String toDbRole(ChatMessage message) {
        // 对齐你表里的约定：USER / AI / SYSTEM；其他类型统一落 OTHER，避免写入失败
        String type = message.type() == null ? null : message.type().name();
        if (type == null) {
            return "OTHER";
        }
        if ("USER".equalsIgnoreCase(type)) return "USER";
        if ("AI".equalsIgnoreCase(type)) return "AI";
        if ("SYSTEM".equalsIgnoreCase(type)) return "SYSTEM";
        return "OTHER";
    }
}

