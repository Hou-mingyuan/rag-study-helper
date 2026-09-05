package com.rag.studyhelper.vector;

import com.rag.studyhelper.config.RagProviderResolver;
import com.rag.studyhelper.mock.MockEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LangChainEmbeddingAdapter implements EmbeddingGateway {

    private final EmbeddingModel model;
    private final int dimension;
    private final String modelName;

    public LangChainEmbeddingAdapter(
            EmbeddingModel model,
            RagProviderResolver providerResolver,
            @Value("${app.vector.dimension:1024}") int configuredDimension,
            @Value("${app.rag.embedding-model-name:unknown}") String configuredModelName) {
        this.model = model;
        this.dimension = providerResolver.isMockMode() ? MockEmbeddingModel.DIMENSION : configuredDimension;
        this.modelName = providerResolver.isMockMode() ? "mock-hash-256" : configuredModelName;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = model.embed(text).content().vector();
        validateDimension(vector);
        return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        List<Embedding> embeddings = model.embedAll(segments).content();
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("Embedding provider returned an incomplete batch");
        }
        return embeddings.stream().map(embedding -> {
            float[] vector = embedding.vector();
            validateDimension(vector);
            return vector;
        }).toList();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private void validateDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalStateException(
                    "Embedding dimension mismatch: expected " + dimension
                            + ", actual " + (vector == null ? 0 : vector.length));
        }
    }
}
