package com.rag.studyhelper.ingestion;

public class IngestionCancelledException extends RuntimeException {

    public IngestionCancelledException() {
        super("Ingestion was cancelled");
    }
}
