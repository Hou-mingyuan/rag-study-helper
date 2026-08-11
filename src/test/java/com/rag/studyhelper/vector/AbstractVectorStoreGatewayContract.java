package com.rag.studyhelper.vector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class AbstractVectorStoreGatewayContract {

    protected VectorStoreGateway store;

    protected abstract VectorStoreGateway createStore();

    protected void cleanUpStore() {
    }

    @BeforeEach
    void setUpContract() {
        store = createStore();
    }

    @AfterEach
    void tearDownContract() {
        cleanUpStore();
    }

    @Test
    void explicitIdsAreSearchableAndDeleteIsIdempotent() {
        store.upsert(List.of(entry("space-1-a", 1L, new float[]{1f, 0f}, "alpha")));

        List<VectorHit> hits = store.search(new VectorQuery(new float[]{1f, 0f}, 1L, 5, 0.1));
        assertEquals(1, hits.size());
        assertEquals("space-1-a", hits.get(0).id());

        store.delete(List.of("space-1-a"));
        store.delete(List.of("space-1-a"));
        assertTrue(store.search(new VectorQuery(new float[]{1f, 0f}, 1L, 5, 0.1)).isEmpty());
        assertTrue(store.status().available());
    }

    @Test
    void searchNeverCrossesKnowledgeSpaces() {
        store.upsert(List.of(
                entry("space-1", 1L, new float[]{1f, 0f}, "space one"),
                entry("space-2", 2L, new float[]{1f, 0f}, "space two")));

        List<VectorHit> hits = store.search(new VectorQuery(new float[]{1f, 0f}, 2L, 5, 0.1));

        assertEquals(List.of("space-2"), hits.stream().map(VectorHit::id).toList());
    }

    @Test
    void repeatedIdReplacesThePreviousVectorAndText() {
        store.upsert(List.of(entry("replace-me", 1L, new float[]{1f, 0f}, "old text")));
        store.upsert(List.of(entry("replace-me", 1L, new float[]{0f, 1f}, "new text")));

        List<VectorHit> hits = store.search(new VectorQuery(new float[]{0f, 1f}, 1L, 5, 0.1));

        assertEquals(1, hits.size());
        assertEquals("replace-me", hits.get(0).id());
        assertEquals("new text", hits.get(0).text());
    }

    protected static EmbeddingGateway contractEmbeddings() {
        return new EmbeddingGateway() {
            @Override
            public float[] embed(String text) {
                throw new UnsupportedOperationException("Contract status only");
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                throw new UnsupportedOperationException("Contract status only");
            }

            @Override
            public int dimension() {
                return 2;
            }

            @Override
            public String modelName() {
                return "contract-2d";
            }
        };
    }

    private VectorEntry entry(String id, long spaceId, float[] vector, String text) {
        return new VectorEntry(id, vector, text, Map.of(
                "spaceId", spaceId,
                "documentId", 1L,
                "chunkId", 1L));
    }
}
