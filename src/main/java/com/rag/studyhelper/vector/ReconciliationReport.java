package com.rag.studyhelper.vector;

public record ReconciliationReport(
        boolean lockAcquired,
        int selected,
        int completed,
        int retried,
        int dead,
        int finalizedDeletes) {
}
