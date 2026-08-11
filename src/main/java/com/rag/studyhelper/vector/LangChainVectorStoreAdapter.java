package com.rag.studyhelper.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
public class LangChainVectorStoreAdapter implements VectorStoreGateway {

    private final EmbeddingStore<TextSegment> store;
    private final String type;
    private final String collection;
    private final EmbeddingGateway embeddings;

    @Autowired
    public LangChainVectorStoreAdapter(
            EmbeddingStore<TextSegment> store,
            @Value("${vector.store.type:in-memory}") String type,
            @Value("${app.vector.collection-name:rag_study_helper_v2}") String collection,
            EmbeddingGateway embeddings) {
        this.store = store;
        this.type = type;
        this.collection = collection;
        this.embeddings = embeddings;
    }

    LangChainVectorStoreAdapter(EmbeddingStore<TextSegment> store,
                                String type, String collection) {
        this.store = store;
        this.type = type;
        this.collection = collection;
        this.embeddings = null;
    }

    @Override
    public void upsert(List<VectorEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(entries.size());
        List<Embedding> embeddings = new ArrayList<>(entries.size());
        List<TextSegment> segments = new ArrayList<>(entries.size());
        for (VectorEntry entry : entries) {
            if (entry == null || entry.id() == null || entry.id().isBlank()) {
                throw new IllegalArgumentException("Vector entry id is required");
            }
            ids.add(entry.id());
            embeddings.add(Embedding.from(entry.vector()));
            segments.add(TextSegment.from(entry.text(), new Metadata(entry.metadata())));
        }
        // LangChain4j 的部分后端把 addAll 实现为纯 insert。入库重试可能已经留下
        // 同一批确定性 ID 的部分数据，因此先按 ID 幂等清理，再写入完整批次。
        store.removeAll(ids);
        store.addAll(ids, embeddings, segments);
    }

    @Override
    public List<VectorHit> search(VectorQuery query) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(query.vector()))
                .maxResults(query.limit())
                .minScore(query.minScore())
                .filter(MetadataFilterBuilder.metadataKey("spaceId").isEqualTo(query.spaceId()))
                .build();
        List<VectorHit> hits = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : store.search(request).matches()) {
            TextSegment segment = match.embedded();
            hits.add(new VectorHit(
                    match.embeddingId(),
                    match.score(),
                    segment == null ? "" : segment.text(),
                    segment == null ? java.util.Map.of() : segment.metadata().toMap()));
        }
        return hits;
    }

    @Override
    public void delete(Collection<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            store.removeAll(ids);
        }
    }

    @Override
    public VectorStoreStatus status() {
        if (embeddings == null) {
            return new VectorStoreStatus(type, collection, true, "contract-test");
        }
        try {
            store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[embeddings.dimension()]))
                    .maxResults(1)
                    .minScore(0d)
                    .build());
            return new VectorStoreStatus(type, collection, true, "reachable");
        } catch (RuntimeException error) {
            return new VectorStoreStatus(type, collection, false,
                    error.getClass().getSimpleName());
        }
    }
}
