package com.rag.studyhelper.vector;

import java.util.Map;

public record VectorEntry(
        String id,
        float[] vector,
        String text,
        Map<String, Object> metadata) {
}
