package com.rag.studyhelper.feishu.client;

import java.util.List;

public record FeishuEnumeration(
        List<WikiNode> nodes,
        boolean complete,
        int pagesFetched,
        int retryCount,
        long maxUpdateTime) {
}
