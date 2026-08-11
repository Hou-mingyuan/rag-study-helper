package com.rag.studyhelper.vector;

import java.util.Collection;
import java.util.List;

public interface VectorStoreGateway {

    void upsert(List<VectorEntry> entries);

    List<VectorHit> search(VectorQuery query);

    void delete(Collection<String> ids);

    VectorStoreStatus status();
}
