-- ============================================================
-- V5__m2.sql —— v2.0 M2：验收向导（eval_run，02-技术方案 §B6）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < zhixing-bootstrap/src/main/resources/db/V5__m2.sql
-- ============================================================

-- 评估运行（F6）：状态机 RUNNING → COMPLETED/FAILED；config 落评估集来源（路径/内联摘要），
-- report 落 {hitAt5, recallAt5, mrrAt10, layers, corpus, delta}——delta 与同库上一个 COMPLETED 运行对比。
CREATE TABLE eval_run (
    id          BIGINT PRIMARY KEY,
    kb_id       BIGINT NOT NULL,
    created_by  BIGINT NOT NULL,
    status      VARCHAR(16) NOT NULL,              -- RUNNING / COMPLETED / FAILED
    config      JSONB NOT NULL,                    -- {source, setPath?, setJsonChars?, setJsonSha256?, corpus:{docs,chunks}}
    report      JSONB,
    error_msg   VARCHAR(500),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    finished_at TIMESTAMP
);
CREATE INDEX idx_eval_run_kb ON eval_run(kb_id, created_at);
