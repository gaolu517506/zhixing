-- ============================================================
-- V7__intent_route.sql —— v2.1 M3 线 G：意图路由（02-技术方案 §B10，F11）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < zhixing-bootstrap/src/main/resources/db/V7__intent_route.sql
-- ============================================================

-- 意图路由规则。02 §B10 原设计 keywords/examples/target_kb_ids 为 PG 原生数组列；
-- 实现改 JSONB（DL-30 ⑧勘误：沿仓库既有约定——eval_run.config/report、audit detail 均
-- JSONB + 实体 String + JsonbTypeHandler，避免为三列新写数组 TypeHandler）。
CREATE TABLE intent_route (
    id             BIGINT PRIMARY KEY,
    name           VARCHAR(64) NOT NULL,
    keywords       JSONB NOT NULL,                   -- ["保修","售后",...]
    examples       JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_kb_ids  JSONB NOT NULL,                   -- [2115..., ...]
    enabled        BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_intent_route_name ON intent_route(name);
