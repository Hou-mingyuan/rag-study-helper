package com.rag.studyhelper.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vector.chroma.url", matches = "https?://.+")
class ChromaVectorStoreContractIT extends AbstractVectorStoreGatewayContract {

    private static final String COLLECTION = "ragsh_contract_chroma_v1";
    private ChromaEmbeddingStore backingStore;

    @Override
    protected VectorStoreGateway createStore() {
        backingStore = ChromaEmbeddingStore.builder()
                .baseUrl(System.getProperty("vector.chroma.url"))
                .collectionName(COLLECTION)
                .build();
        backingStore.removeAll();
        EmbeddingStore<TextSegment> store = backingStore;
        return new LangChainVectorStoreAdapter(
                store, "chroma", COLLECTION, contractEmbeddings());
    }

    @Override
    protected void cleanUpStore() {
        if (backingStore != null) {
            backingStore.removeAll();
        }
    }
}
