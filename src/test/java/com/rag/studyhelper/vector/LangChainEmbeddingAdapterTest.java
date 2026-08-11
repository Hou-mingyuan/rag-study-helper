package com.rag.studyhelper.vector;

import com.rag.studyhelper.config.RagProviderResolver;
import com.rag.studyhelper.mock.MockEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChainEmbeddingAdapterTest {

    @Test
    void mockModeUsesDeterministicModelMetadataAndSupportsBatches() {
        RagProviderResolver resolver = mock(RagProviderResolver.class);
        when(resolver.isMockMode()).thenReturn(true);
        LangChainEmbeddingAdapter adapter = new LangChainEmbeddingAdapter(
                new MockEmbeddingModel(), resolver, 1024, "ignored");

        assertEquals(MockEmbeddingModel.DIMENSION, adapter.dimension());
        assertEquals("mock-hash-256", adapter.modelName());
        assertEquals(MockEmbeddingModel.DIMENSION, adapter.embed("向量检索").length);
        assertEquals(2, adapter.embedAll(List.of("第一段", "第二段")).size());
    }

    @Test
    void configuredModeRejectsSingleAndBatchDimensionDrift() {
        RagProviderResolver resolver = mock(RagProviderResolver.class);
        when(resolver.isMockMode()).thenReturn(false);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("bad")).thenReturn(Response.from(Embedding.from(new float[2])));
        when(model.embedAll(org.mockito.ArgumentMatchers.<List<TextSegment>>any()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[3]))));
        LangChainEmbeddingAdapter adapter = new LangChainEmbeddingAdapter(
                model, resolver, 4, "provider-model");

        assertEquals(4, adapter.dimension());
        assertEquals("provider-model", adapter.modelName());
        assertThrows(IllegalStateException.class, () -> adapter.embed("bad"));
        assertThrows(IllegalStateException.class, () -> adapter.embedAll(List.of("bad")));
    }

    @Test
    void incompleteProviderBatchIsRejectedBeforeIndexing() {
        RagProviderResolver resolver = mock(RagProviderResolver.class);
        when(resolver.isMockMode()).thenReturn(false);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(org.mockito.ArgumentMatchers.<List<TextSegment>>any()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[4]))));
        LangChainEmbeddingAdapter adapter = new LangChainEmbeddingAdapter(
                model, resolver, 4, "provider-model");

        assertThrows(IllegalStateException.class,
                () -> adapter.embedAll(List.of("one", "two")));
    }
}
