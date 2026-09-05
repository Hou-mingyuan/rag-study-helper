package com.rag.studyhelper.ingestion;

public record DocumentDescriptor(
        long spaceId,
        String documentName,
        String documentType,
        String mimeType,
        String source,
        String contentHash,
        long fileSize,
        String originalPath,
        String remoteSpaceId,
        String feishuNodeToken,
        String feishuObjType,
        long feishuUpdateTime,
        String creator) {
}
