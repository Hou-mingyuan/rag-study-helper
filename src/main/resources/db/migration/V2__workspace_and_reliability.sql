CREATE TABLE knowledge_spaces (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500) NOT NULL DEFAULT '',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO knowledge_spaces (id, name, description, status)
VALUES (1, 'Default', 'Default knowledge space migrated from v1', 'ACTIVE');

ALTER TABLE documents
    DROP INDEX uk_content_hash,
    DROP INDEX uk_feishu_node,
    ADD COLUMN space_id BIGINT NOT NULL DEFAULT 1 AFTER id,
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'READY' AFTER space_id,
    ADD COLUMN current_version INT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN pending_version INT DEFAULT NULL AFTER current_version,
    ADD COLUMN mime_type VARCHAR(120) DEFAULT NULL AFTER document_type,
    ADD COLUMN original_path VARCHAR(1000) DEFAULT NULL AFTER file_size,
    ADD COLUMN remote_space_id VARCHAR(128) DEFAULT NULL AFTER feishu_obj_type,
    ADD COLUMN remote_missing_count INT NOT NULL DEFAULT 0 AFTER feishu_update_time,
    ADD COLUMN last_seen_sync_run_id BIGINT DEFAULT NULL AFTER remote_missing_count,
    ADD COLUMN last_error VARCHAR(1000) DEFAULT NULL AFTER last_seen_sync_run_id,
    ADD COLUMN deleted_at DATETIME DEFAULT NULL AFTER last_error,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER deleted_at,
    ADD UNIQUE KEY uk_space_content_hash (space_id, content_hash),
    ADD UNIQUE KEY uk_space_feishu_node (space_id, remote_space_id, feishu_node_token),
    ADD INDEX idx_documents_space_status (space_id, status),
    ADD INDEX idx_documents_remote_seen (space_id, remote_space_id, last_seen_sync_run_id),
    ADD CONSTRAINT fk_documents_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id);

ALTER TABLE document_chunks
    ADD COLUMN space_id BIGINT DEFAULT NULL AFTER id,
    ADD COLUMN document_version INT NOT NULL DEFAULT 1 AFTER document_id,
    ADD COLUMN chunk_hash VARCHAR(64) DEFAULT NULL AFTER chunk_index,
    ADD COLUMN section_title VARCHAR(255) DEFAULT NULL AFTER chunk_hash,
    ADD COLUMN page_number INT DEFAULT NULL AFTER section_title,
    ADD COLUMN start_offset INT DEFAULT NULL AFTER page_number,
    ADD COLUMN end_offset INT DEFAULT NULL AFTER start_offset,
    ADD COLUMN token_count INT NOT NULL DEFAULT 0 AFTER end_offset,
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'READY' AFTER token_count,
    ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER create_time;

UPDATE document_chunks c
JOIN documents d ON d.id = c.document_id
SET c.space_id = d.space_id
WHERE c.space_id IS NULL;

ALTER TABLE document_chunks
    MODIFY COLUMN space_id BIGINT NOT NULL,
    ADD UNIQUE KEY uk_chunk_vector_id (vector_id),
    ADD UNIQUE KEY uk_document_version_chunk (document_id, document_version, chunk_index),
    ADD INDEX idx_chunks_space_status (space_id, status),
    ADD CONSTRAINT fk_chunks_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    ADD CONSTRAINT fk_chunks_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

CREATE TABLE document_versions (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id            BIGINT NOT NULL,
    document_id         BIGINT NOT NULL,
    version_number      INT NOT NULL,
    status              VARCHAR(24) NOT NULL,
    document_name       VARCHAR(255) NOT NULL,
    document_type       VARCHAR(20) NOT NULL,
    mime_type           VARCHAR(120) DEFAULT NULL,
    source              VARCHAR(20) NOT NULL,
    content_hash        VARCHAR(64) DEFAULT NULL,
    file_size           BIGINT NOT NULL DEFAULT 0,
    original_path       VARCHAR(1000) DEFAULT NULL,
    feishu_update_time  BIGINT NOT NULL DEFAULT 0,
    chunk_count         INT NOT NULL DEFAULT 0,
    error_message       VARCHAR(1000) DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_version (document_id, version_number),
    INDEX idx_versions_space_hash (space_id, content_hash, status),
    CONSTRAINT fk_versions_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    CONSTRAINT fk_versions_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ingestion_jobs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id            BIGINT NOT NULL,
    document_id         BIGINT DEFAULT NULL,
    operation           VARCHAR(24) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    idempotency_key     VARCHAR(160) NOT NULL,
    file_name           VARCHAR(255) DEFAULT NULL,
    payload_path        VARCHAR(1000) DEFAULT NULL,
    progress_current    INT NOT NULL DEFAULT 0,
    progress_total      INT NOT NULL DEFAULT 0,
    attempts            INT NOT NULL DEFAULT 0,
    max_attempts        INT NOT NULL DEFAULT 3,
    cancel_requested    BOOLEAN NOT NULL DEFAULT FALSE,
    error_code          VARCHAR(80) DEFAULT NULL,
    error_message       VARCHAR(1000) DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    start_time          DATETIME DEFAULT NULL,
    finish_time         DATETIME DEFAULT NULL,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_job_idempotency (space_id, idempotency_key),
    INDEX idx_jobs_space_status (space_id, status, create_time),
    CONSTRAINT fk_jobs_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    CONSTRAINT fk_jobs_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vector_reconciliation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id            BIGINT NOT NULL,
    document_id         BIGINT DEFAULT NULL,
    chunk_id            BIGINT DEFAULT NULL,
    vector_id           VARCHAR(64) NOT NULL,
    operation           VARCHAR(16) NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts            INT NOT NULL DEFAULT 0,
    last_error          VARCHAR(1000) DEFAULT NULL,
    next_retry_time     DATETIME DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vector_pending_operation (vector_id, operation, status),
    INDEX idx_vector_reconcile_due (status, next_retry_time),
    CONSTRAINT fk_vector_reconcile_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    CONSTRAINT fk_vector_reconcile_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE SET NULL,
    CONSTRAINT fk_vector_reconcile_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunks(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feishu_sync_runs (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                BIGINT NOT NULL,
    remote_space_id         VARCHAR(128) NOT NULL,
    status                  VARCHAR(24) NOT NULL,
    enumeration_complete    BOOLEAN NOT NULL DEFAULT FALSE,
    remote_cursor           VARCHAR(255) DEFAULT NULL,
    pages_fetched           INT NOT NULL DEFAULT 0,
    nodes_seen              INT NOT NULL DEFAULT 0,
    nodes_created           INT NOT NULL DEFAULT 0,
    nodes_updated           INT NOT NULL DEFAULT 0,
    nodes_skipped           INT NOT NULL DEFAULT 0,
    nodes_failed            INT NOT NULL DEFAULT 0,
    delete_candidates       INT NOT NULL DEFAULT 0,
    nodes_deleted           INT NOT NULL DEFAULT 0,
    deletes_protected       INT NOT NULL DEFAULT 0,
    retry_count             INT NOT NULL DEFAULT 0,
    guard_reason            VARCHAR(500) DEFAULT NULL,
    error_summary           VARCHAR(1000) DEFAULT NULL,
    start_time              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time             DATETIME DEFAULT NULL,
    create_time             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_feishu_run_space (space_id, remote_space_id, start_time),
    CONSTRAINT fk_feishu_runs_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_sessions (
    id                  VARCHAR(64) PRIMARY KEY,
    space_id            BIGINT NOT NULL,
    title               VARCHAR(160) NOT NULL DEFAULT 'New conversation',
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_message_at     DATETIME DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_chat_sessions_space (space_id, update_time),
    CONSTRAINT fk_chat_sessions_space FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vector_index_metadata (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_type          VARCHAR(32) NOT NULL,
    collection_name     VARCHAR(128) NOT NULL,
    schema_version      INT NOT NULL,
    embedding_model     VARCHAR(255) NOT NULL,
    dimension_value     INT NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'READY',
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vector_index (store_type, collection_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
