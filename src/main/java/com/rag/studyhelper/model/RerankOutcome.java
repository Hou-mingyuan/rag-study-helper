package com.rag.studyhelper.model;

import java.util.List;

public record RerankOutcome(
        List<RankedCandidate> candidates,
        String mode,
        boolean fallback,
        String detail) {
}
