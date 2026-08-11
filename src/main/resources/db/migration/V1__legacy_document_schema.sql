CREATE TABLE IF NOT EXISTS documents (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_name       VARCHAR(255) NOT NULL,
    document_type       VARCHAR(20) NOT NULL,
    source              VARCHAR(20) NOT NULL,
    content_hash        VARCHAR(64) DEFAULT NULL,
    file_size           BIGINT NOT NULL DEFAULT 0,
    chunk_count         INT NOT NULL DEFAULT 0,
    feishu_node_token   VARCHAR(64) DEFAULT NULL,
    feishu_obj_type     VARCHAR(20) DEFAULT NULL,
    feishu_update_time  BIGINT NOT NULL DEFAULT 0,
    creator             VARCHAR(64) NOT NULL DEFAULT 'system',
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_hash (content_hash),
    UNIQUE KEY uk_feishu_node (feishu_node_token),
    INDEX idx_document_name (document_name),
    INDEX idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_chunks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT NOT NULL,
    vector_id       VARCHAR(64) NOT NULL,
    chunk_index     INT NOT NULL,
    chunk_text      TEXT NOT NULL,
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_document_id (document_id),
    INDEX idx_vector_id (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
