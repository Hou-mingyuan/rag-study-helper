package com.rag.studyhelper.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vector.milvus.host", matches = ".+")
class MilvusVectorStoreContractIT extends AbstractVectorStoreGatewayContract {

    private static final String COLLECTION = "ragsh_contract_milvus_v1";
    private MilvusEmbeddingStore backingStore;

    @Override
    protected VectorStoreGateway createStore() {
        int port = Integer.parseInt(System.getProperty("vector.milvus.port", "19055"));
        backingStore = MilvusEmbeddingStore.builder()
                .host(System.getProperty("vector.milvus.host"))
                .port(port)
                .collectionName(COLLECTION)
                .dimension(2)
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        EmbeddingStore<TextSegment> store = backingStore;
        return new LangChainVectorStoreAdapter(
                store, "milvus", COLLECTION, contractEmbeddings());
    }

    @Override
    protected void cleanUpStore() {
        if (backingStore != null) {
            backingStore.dropCollection(COLLECTION);
        }
    }
}
