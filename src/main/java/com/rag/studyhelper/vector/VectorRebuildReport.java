package com.rag.studyhelper.vector;

public record VectorRebuildReport(
        String storeType,
        String collection,
        String embeddingModel,
        int dimension,
        long activeChunks,
        long indexedChunks,
        String status) {
}
