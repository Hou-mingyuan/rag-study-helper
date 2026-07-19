package com.rag.studyhelper.service;

import com.rag.studyhelper.model.RetrievalSnippet;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryServiceSnippetTest {

    @Test
    void toRetrievalSnippet_stripsSourcePrefixAndTruncates() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longBody.append("向量检索把问题和文档 chunk 都转成 Embedding 向量。");
        }
        TextSegment segment = TextSegment.from("[来源:vector-retrieval-basics.md]\n" + longBody);

        RetrievalSnippet snippet = RagQueryService.toRetrievalSnippet(segment);

        assertEquals("vector-retrieval-basics.md", snippet.getDocumentName());
        assertTrue(snippet.getText().endsWith("…"));
        assertTrue(snippet.getText().length() <= 321);
    }

    @Test
    void extractSourceName_defaultsWhenMissingPrefix() {
        assertEquals("参考文档", RagQueryService.extractSourceName("plain chunk"));
    }
}
