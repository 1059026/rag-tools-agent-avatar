package com.example.demo.service;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class AgentService {

    private final Assistant assistant;
    private final ChatMemoryStore chatMemoryStore;

    public AgentService(Assistant assistant, ChatMemoryStore chatMemoryStore) {
        this.assistant = assistant;
        this.chatMemoryStore = chatMemoryStore;
    }

    public Flux<String> ping(String sessionId, String message) {
        return assistant.chat(sessionId, message);
    }

    /** 清空指定会话在持久化存储中的 LangChain4j 消息，用于 /new 重置上下文 */
    public void clearChatMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        chatMemoryStore.deleteMessages(sessionId);
    }
}
