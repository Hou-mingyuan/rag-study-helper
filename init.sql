-- ===================================================================
--  RAG Demo - 数据库初始化脚本
--  版本: V1.0
--  说明: 文档元数据 + 分块映射表
--  注意: 字符集使用 utf8mb4 以支持中文和 emoji
-- ===================================================================

CREATE TABLE IF NOT EXISTS documents (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_name       VARCHAR(255) NOT NULL COMMENT '文档显示名称',
    document_type       VARCHAR(20)  NOT NULL COMMENT '文件类型: pdf|txt|md|docx|xlsx|pptx|html',
    source              VARCHAR(20)  NOT NULL COMMENT '来源: UPLOAD | SCAN | FEISHU',
    content_hash        VARCHAR(64)  DEFAULT NULL COMMENT '内容SHA256，上传/扫描去重用',
    file_size           BIGINT       DEFAULT 0 COMMENT '文件大小（字节）',
    chunk_count         INT          DEFAULT 0 COMMENT '向量化分块数',
    feishu_node_token   VARCHAR(64)  DEFAULT NULL COMMENT '飞书节点token，同步判重用',
    feishu_obj_type     VARCHAR(20)  DEFAULT NULL COMMENT '飞书对象类型: doc|docx|sheet|bitable',
    feishu_update_time  BIGINT       DEFAULT 0 COMMENT '飞书文档更新时间戳',
    creator             VARCHAR(64)  NOT NULL DEFAULT 'system' COMMENT '创建人',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_content_hash (content_hash),
    UNIQUE KEY uk_feishu_node (feishu_node_token),
    INDEX idx_document_name (document_name),
    INDEX idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档元数据表';

CREATE TABLE IF NOT EXISTS document_chunks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT       NOT NULL COMMENT '所属文档ID',
    vector_id       VARCHAR(64)  NOT NULL COMMENT '向量库中的chunk ID',
    chunk_index     INT          NOT NULL COMMENT '分块序号',
    chunk_text      TEXT         NOT NULL COMMENT '分块文本',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_document_id (document_id),
    INDEX idx_vector_id (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块映射表';
