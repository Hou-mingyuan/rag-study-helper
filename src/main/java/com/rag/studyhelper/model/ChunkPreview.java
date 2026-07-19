package com.rag.studyhelper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChunkPreview {

    private int chunkIndex;

    private String preview;

    private int charCount;
}
