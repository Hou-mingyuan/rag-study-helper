package com.rag.studyhelper.vector;

public record VectorStoreStatus(
        String type,
        String collection,
        boolean available,
        String detail) {
}
