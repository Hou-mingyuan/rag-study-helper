ALTER TABLE vector_index_metadata
    ADD COLUMN entry_count BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN last_error VARCHAR(1000) DEFAULT NULL AFTER entry_count,
    ADD COLUMN last_rebuild_time DATETIME DEFAULT NULL AFTER last_error;
