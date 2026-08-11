package com.rag.studyhelper.mock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MockEmbeddingModelTest {

    @Test
    void chineseTextUsesOverlappingBigramsAndTrigrams() {
        var tokens = MockEmbeddingModel.tokenize("向量检索用于知识库");

        assertTrue(tokens.contains("向量"));
        assertTrue(tokens.contains("向量检"));
        assertTrue(tokens.contains("检索"));
        assertTrue(tokens.contains("知识库"));
    }

    @Test
    void mixedTextKeepsAsciiModelNames() {
        var tokens = MockEmbeddingModel.tokenize("使用 Chroma 与 Milvus-2 向量库");

        assertTrue(tokens.contains("chroma"));
        assertTrue(tokens.contains("milvus-2"));
        assertTrue(tokens.contains("向量"));
    }
}
