package com.rag.studyhelper.ingestion;

public record ChunkDraft(
        int index,
        String text,
        String contentHash,
        String sectionTitle,
        Integer pageNumber,
        Integer startOffset,
        Integer endOffset,
        int tokenCount) {
}
