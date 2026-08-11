package com.rag.studyhelper.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

class VectorStoreGatewayContractTest extends AbstractVectorStoreGatewayContract {

    @Override
    protected VectorStoreGateway createStore() {
        return new LangChainVectorStoreAdapter(
                new InMemoryEmbeddingStore<TextSegment>(),
                "in-memory",
                "contract-in-memory",
                contractEmbeddings());
    }
}
