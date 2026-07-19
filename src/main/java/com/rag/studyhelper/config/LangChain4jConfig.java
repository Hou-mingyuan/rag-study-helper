package com.rag.studyhelper.config;

import com.rag.studyhelper.mock.MockChatModels;
import com.rag.studyhelper.mock.MockEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * LangChain4j 配置 — openai 或 mock（零密钥演示）
 */
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.open-ai.chat-model.temperature}")
    private Double temperature;

    @Value("${langchain4j.open-ai.embedding-model.api-key}")
    private String embeddingApiKey;

    @Value("${langchain4j.open-ai.embedding-model.base-url}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String embeddingModelName;

    @Bean
    @ConditionalOnProperty(name = "vector.store.type", havingValue = "in-memory", matchIfMissing = true)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Value("${chroma.host:localhost}")
    private String chromaHost;

    @Value("${chroma.port:8000}")
    private Integer chromaPort;

    @Value("${chroma.collection-name:rag_study_helper}")
    private String chromaCollectionName;

    @Bean
    @ConditionalOnProperty(name = "vector.store.type", havingValue = "chroma")
    @Lazy
    public EmbeddingStore<TextSegment> chromaEmbeddingStore() {
        return ChromaEmbeddingStore.builder()
                .baseUrl("http://" + chromaHost + ":" + chromaPort)
                .collectionName(chromaCollectionName)
                .build();
    }

    @Value("${milvus.host:localhost}")
    private String milvusHost;

    @Value("${milvus.port:19530}")
    private Integer milvusPort;

    @Value("${milvus.collection-name:rag_study_helper}")
    private String milvusCollectionName;

    @Value("${milvus.dimension:2048}")
    private Integer milvusDimension;

    @Bean
    @ConditionalOnProperty(name = "vector.store.type", havingValue = "milvus")
    @Lazy
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        return MilvusEmbeddingStore.builder()
                .host(milvusHost)
                .port(milvusPort)
                .collectionName(milvusCollectionName)
                .dimension(milvusDimension)
                .build();
    }

    @Bean
    public ChatLanguageModel chatLanguageModel(RagProviderResolver provider) {
        if (provider.isMockMode()) {
            return MockChatModels.chatLanguageModel();
        }
        return OpenAiChatModel.builder()
                .apiKey(chatApiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                .tokenizer(new OpenAiTokenizer(OpenAiChatModelName.GPT_3_5_TURBO))
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel(RagProviderResolver provider) {
        if (provider.isMockMode()) {
            return MockChatModels.streamingChatLanguageModel();
        }
        return OpenAiStreamingChatModel.builder()
                .apiKey(chatApiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                .tokenizer(new OpenAiTokenizer(OpenAiChatModelName.GPT_3_5_TURBO))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(RagProviderResolver provider) {
        if (provider.isMockMode()) {
            return new MockEmbeddingModel();
        }
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
