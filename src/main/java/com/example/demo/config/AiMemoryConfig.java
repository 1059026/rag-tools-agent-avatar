package com.example.demo.config;

import com.example.demo.service.PersistentChatMemoryStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiMemoryConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallEnV15QuantizedEmbeddingModel();
    }

    @Bean
    ChatMemoryStore chatMemoryStore(PersistentChatMemoryStore store) {
        return store;
    }

    @Bean
    ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        return chatId -> MessageWindowChatMemory.builder()
                .id(chatId)
                .maxMessages(10)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }
    @Bean
    // 构建向量数据库操作对象
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        //1.加载文档进内存
        List<Document> documents = FileSystemDocumentLoader.loadDocuments("/Users/rodman/workspace/行业知识库文档");
        //2.构建向量数据库操作对象
        InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        //3.构建一个EmbeddingStoreIngestor对象，完成文本数据切割以及向量化存储
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
        //4.执行向量化存储
        ingestor.ingest(documents);
        return store;
    }
    //构建向量数据库检索对象
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> store) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .minScore(0.5)
                .maxResults(3)
                .build();
    }
}

