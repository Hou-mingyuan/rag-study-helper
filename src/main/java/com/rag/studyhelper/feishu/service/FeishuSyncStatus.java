package com.rag.studyhelper.feishu.service;

public record FeishuSyncStatus(
        boolean enabled,
        long localSpaceId,
        String remoteSpaceId) {
}
