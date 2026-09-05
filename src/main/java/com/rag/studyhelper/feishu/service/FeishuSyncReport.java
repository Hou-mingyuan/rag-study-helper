package com.rag.studyhelper.feishu.service;

public record FeishuSyncReport(
        long runId,
        String status,
        boolean enumerationComplete,
        int nodesSeen,
        int created,
        int updated,
        int skipped,
        int failed,
        int deleteCandidates,
        int deleted,
        int protectedDeletes,
        int retries,
        String guardReason,
        String errorSummary) {
}
