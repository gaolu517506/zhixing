-- ============================================================
-- V3__memory.sql —— 长期记忆建表（来源：docs/design/14-详设-v1.0 §3.2，D12 落地）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < src/main/resources/db/V3__memory.sql
-- ============================================================

CREATE TABLE agent_memory (
    id             BIGINT PRIMARY KEY,
    content        TEXT NOT NULL,            -- 事实文本（一句一条）
    status         VARCHAR(16) NOT NULL,     -- ACTIVE / SUPERSEDED / RETRACTED
    superseded_by  BIGINT,                   -- SUPERSEDED 指向替代它的新记忆
    source_session_id BIGINT,                -- 产出该记忆的会话（溯源）
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_memory_status ON agent_memory(status, updated_at DESC);
