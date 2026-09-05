package com.rag.studyhelper.ingestion;

import com.rag.studyhelper.model.DocumentInfo;

import java.util.List;

public record ActivationResult(DocumentInfo document, List<String> staleVectorIds) {
}
