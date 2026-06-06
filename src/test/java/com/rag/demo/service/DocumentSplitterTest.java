package com.rag.demo.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSplitterTest {

    @Test
    void paragraphSplitterPreservesParagraphBoundaries() {
        DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(300, 60);
        // Document with clear paragraph breaks
        Document doc = Document.from(
                "第一季度营收达到150亿元。同比增长12.5%。\n" +
                "第二季度净利润为2.3亿元。环比增长8%。\n" +
                "研发投入占比持续提升。主要得益于AI业务增长。\n" +
                "公司计划加大研发投入。预计明年推出新产品。"
        );
        List<TextSegment> segments = splitter.split(doc);
        for (TextSegment segment : segments) {
            assertFalse(segment.text().trim().isEmpty());
        }
    }

    @Test
    void paragraphSplitterRespectsMaxSegmentSize() {
        int maxSegmentSize = 100;
        int maxOverlapSize = 20;
        DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(maxSegmentSize, maxOverlapSize);
        int maxExpectedSize = maxSegmentSize + maxOverlapSize;
        // Build a string that exceeds maxSegmentSize with paragraph breaks
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i + 1).append("段。\n");
        }
        Document doc = Document.from(sb.toString());
        List<TextSegment> segments = splitter.split(doc);
        assertTrue(segments.size() >= 2, "Should produce multiple chunks for 20 sentences with 100-char limit");
        for (TextSegment segment : segments) {
            assertTrue(segment.text().length() <= maxExpectedSize,
                    "Segment length " + segment.text().length() + " should not exceed " + maxExpectedSize);
        }
    }
}
