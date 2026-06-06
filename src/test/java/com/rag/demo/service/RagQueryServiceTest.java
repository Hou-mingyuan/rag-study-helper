package com.rag.demo.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    private static final String TEST_SESSION = "test-session";
    private static final Embedding DUMMY_EMBEDDING = new Embedding(new float[]{0.1f, 0.2f, 0.3f});

    @Mock
    private ConversationStore conversationStore;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private RerankService rerankService;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private OpenAiStreamingChatModel streamingChatModel;

    private RagQueryService ragQueryService;

    @BeforeEach
    void setUp() {
        // Default: no history, no rewrite
        lenient().when(conversationStore.getHistory(anyString()))
                .thenReturn(Collections.emptyList());
        lenient().when(queryRewriteService.rewrite(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(DUMMY_EMBEDDING));

        ragQueryService = new RagQueryService();
        ReflectionTestUtils.setField(ragQueryService, "conversationStore", conversationStore);
        ReflectionTestUtils.setField(ragQueryService, "queryRewriteService", queryRewriteService);
        ReflectionTestUtils.setField(ragQueryService, "rerankService", rerankService);
        ReflectionTestUtils.setField(ragQueryService, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(ragQueryService, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(ragQueryService, "streamingChatModel", streamingChatModel);
        ReflectionTestUtils.setField(ragQueryService, "scoreThreshold", 0.80);
    }

    @Test
    void serviceShouldHaveRerankField() {
        Object injected = ReflectionTestUtils.getField(ragQueryService, "rerankService");
        assertEquals(rerankService, injected);
    }

    @Test
    void shouldCallRerankWhenDocumentsFound() {
        TextSegment segment = TextSegment.from("测试文档内容 [来源:test.pdf]");
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                0.95, "id1", DUMMY_EMBEDDING, segment);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(Arrays.asList(match)));
        when(rerankService.rerank(anyString(), anyList()))
                .thenReturn(Arrays.asList(segment));

        ragQueryService.streamAnswer(TEST_SESSION, "test question", new RagQueryService.StreamingCallback() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
        });

        verify(rerankService).rerank(eq("test question"), anyList());
    }

    @Test
    void shouldNotCallRerankWhenNoDocumentsFound() {
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                0.30, "id1", DUMMY_EMBEDDING, TextSegment.from("irrelevant"));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(Arrays.asList(match)));

        ragQueryService.streamAnswer(TEST_SESSION, "test question", new RagQueryService.StreamingCallback() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
        });

        verify(rerankService, never()).rerank(anyString(), anyList());
    }

    @Test
    void shouldUseRagPromptWhenDocumentsFound() {
        TextSegment segment = TextSegment.from("测试文档内容 [来源:test.pdf]");
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(
                0.95, "id1", DUMMY_EMBEDDING, segment);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(Arrays.asList(match)));
        when(rerankService.rerank(anyString(), anyList()))
                .thenReturn(Arrays.asList(segment));

        ragQueryService.streamAnswer(TEST_SESSION, "test question", new RagQueryService.StreamingCallback() {
            @Override public void onToken(String token) {}
            @Override public void onComplete() {}
            @Override public void onError(Throwable error) {}
        });

        verify(streamingChatModel).generate(anyString(),
                isA(StreamingResponseHandler.class));
    }
}
