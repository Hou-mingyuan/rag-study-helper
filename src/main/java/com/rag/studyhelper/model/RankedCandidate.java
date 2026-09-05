package com.rag.studyhelper.model;

public record RankedCandidate(
        String id,
        String text,
        double retrievalScore,
        Double rerankScore) {
}
