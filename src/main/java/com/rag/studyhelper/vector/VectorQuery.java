package com.rag.studyhelper.vector;

public record VectorQuery(
        float[] vector,
        long spaceId,
        int limit,
        double minScore) {
}
