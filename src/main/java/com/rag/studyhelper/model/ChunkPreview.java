package com.rag.studyhelper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChunkPreview {

    private Long chunkId;

    private int chunkIndex;

    private String preview;

    private int charCount;

    private String sectionTitle;

    private Integer pageNumber;

    private Integer startOffset;

    private Integer endOffset;

    public ChunkPreview(int chunkIndex, String preview, int charCount) {
        this(null, chunkIndex, preview, charCount, null, null, null, null);
    }
}
