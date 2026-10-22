-- ============================================================
-- V2__agent.sql —— 阶段 3 ReAct Agent 建表（来源：docs/design/13-详设-阶段3-ReAct与容错.md §3）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < src/main/resources/db/V2__agent.sql
-- ============================================================

-- Agent 运行（挂起恢复的全部状态：messages_snapshot + pending_tool）
CREATE TABLE agent_run (
    id                 BIGINT PRIMARY KEY,
    session_id         BIGINT,
    kb_id              BIGINT,
    input              TEXT NOT NULL,
    status             VARCHAR(20) NOT NULL,          -- RUNNING/WAITING_CONFIRM/COMPLETED/FAILED/CANCELLED
    iterations         INT,
    messages_snapshot  JSONB,                         -- 全量 messages（恢复用）
    pending_tool       JSONB,                         -- {toolCallId, name, arguments}
    error_msg          TEXT,
    prompt_tokens      INT,
    completion_tokens  INT,
    cost_ms            INT,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP NOT NULL DEFAULT now()
);

-- Agent 步骤流水（每轮 LLM 推理与每次工具执行各一条）
CREATE TABLE agent_run_step (
    id          BIGINT PRIMARY KEY,
    run_id      BIGINT NOT NULL,
    iter        INT NOT NULL,
    type        VARCHAR(16) NOT NULL,                -- LLM / TOOL
    tool_name   VARCHAR(64),
    arguments   TEXT,                                -- 截断 2000 落库
    result      TEXT,                                -- 截断 2000 落库
    cost_ms     INT,
    status      VARCHAR(16) NOT NULL,                -- SUCCESS / FAILED
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_run_step ON agent_run_step(run_id, iter);
CREATE INDEX idx_run_session ON agent_run(session_id);
