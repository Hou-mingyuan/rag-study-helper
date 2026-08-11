package com.rag.studyhelper.ingestion;

public interface IngestionControl {

    IngestionControl NONE = new IngestionControl() {
        @Override
        public void progress(String phase, int current, int total) {
        }

        @Override
        public boolean isCancellationRequested() {
            return false;
        }
    };

    void progress(String phase, int current, int total);

    boolean isCancellationRequested();
}
