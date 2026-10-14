-- ============================================================
-- V1__init.sql —— 知行阶段 1 全量建表（来源：docs/design/02-详设-阶段1 §2）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < src/main/resources/db/V1__init.sql
-- agent_run / agent_run_step 两表阶段 3 迁移脚本 V2 提供，本阶段不建。
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 知识库
CREATE TABLE kb_knowledge_base (
    id             BIGINT PRIMARY KEY,
    name           VARCHAR(64)  NOT NULL,
    description    VARCHAR(512),
    chunk_size     INT NOT NULL DEFAULT 500,   -- 分块目标长度（字符）
    chunk_overlap  INT NOT NULL DEFAULT 50,    -- 相邻块重叠（字符）
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);

-- 文档（status 是本阶段唯一的显式状态机）
CREATE TABLE kb_document (
    id          BIGINT PRIMARY KEY,
    kb_id       BIGINT NOT NULL,
    file_name   VARCHAR(256) NOT NULL,
    file_path   VARCHAR(512) NOT NULL,          -- 磁盘相对路径
    file_hash   VARCHAR(64),                    -- SHA-256，幂等判重
    file_type   VARCHAR(32),
    file_size   BIGINT,
    status      VARCHAR(16) NOT NULL,           -- UPLOADED/PARSING/CHUNKING/EMBEDDING/COMPLETED/FAILED
    chunk_count INT,
    error_msg   TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_kb ON kb_document(kb_id);

-- 分块（核心表：向量索引 + 全文索引都在这里）
CREATE TABLE kb_chunk (
    id          BIGINT PRIMARY KEY,
    kb_id       BIGINT NOT NULL,
    doc_id      BIGINT NOT NULL,
    chunk_index INT  NOT NULL,                  -- 在文档内的序号
    content     TEXT NOT NULL,
    token_count INT,
    embedding   vector(1024),                   -- 维度绑定智谱 embedding-3（dimensions=1024）；换 embedding 模型必须重建本列+索引
    metadata    JSONB DEFAULT '{}',             -- {page, heading} 等富化信息，阶段 1 可空
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_chunk_hnsw  ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_trgm  ON kb_chunk USING gin (content gin_trgm_ops);
CREATE INDEX idx_chunk_doc   ON kb_chunk(doc_id);

-- 会话/消息（阶段 1 只做存取；summary 字段阶段 2 启用）
CREATE TABLE qa_session (
    id                 BIGINT PRIMARY KEY,
    title              VARCHAR(128),
    summary_text       TEXT,
    summarized_msg_id  BIGINT,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE qa_message (
    id                BIGINT PRIMARY KEY,
    session_id        BIGINT NOT NULL,
    role              VARCHAR(16) NOT NULL,      -- user / assistant
    content           TEXT NOT NULL,
    engine            VARCHAR(16) DEFAULT 'workflow',
    sources           JSONB,                     -- assistant 专属：引用来源数组
    prompt_tokens     INT,
    completion_tokens INT,
    cost_ms           INT,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_session ON qa_message(session_id, id);

-- Trace（阶段 1 就建，边写边埋点，养成习惯）
CREATE TABLE trace_run (
    id            BIGINT PRIMARY KEY,
    biz_type      VARCHAR(16) NOT NULL,          -- QA / AGENT / INGEST
    biz_id        BIGINT,
    status        VARCHAR(16) NOT NULL,          -- RUNNING/COMPLETED/FAILED
    total_cost_ms INT,
    error_msg     TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE trace_node (
    id        BIGINT PRIMARY KEY,
    run_id    BIGINT NOT NULL,
    name      VARCHAR(64) NOT NULL,              -- vector_search / keyword_search / fusion / rerank / llm_chat...
    seq       INT NOT NULL,                      -- 同一 run 内递增
    cost_ms   INT,
    input     TEXT,                              -- 截断到 2000 字符落库
    output    TEXT,
    status    VARCHAR(16) NOT NULL,
    error     TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_trace_node_run ON trace_node(run_id, seq);
