package com.rag.studyhelper.vector;

import java.util.Map;

public record VectorHit(
        String id,
        double score,
        String text,
        Map<String, Object> metadata) {
}
