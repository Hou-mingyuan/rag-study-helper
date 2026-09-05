package com.rag.studyhelper.model;

public record ChunkDetail(
        long chunkId,
        int chunkIndex,
        String text,
        int charCount,
        String sectionTitle,
        Integer pageNumber,
        Integer startOffset,
        Integer endOffset) {
}
