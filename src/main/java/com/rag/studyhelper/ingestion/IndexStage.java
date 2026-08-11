package com.rag.studyhelper.ingestion;

import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentVersion;
import com.rag.studyhelper.model.Documents;

import java.util.List;

public record IndexStage(
        Documents document,
        DocumentVersion version,
        List<DocumentChunks> chunks,
        boolean duplicate) {

    public static IndexStage duplicate(Documents document) {
        return new IndexStage(document, null, List.of(), true);
    }
}
