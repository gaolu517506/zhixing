-- ============================================================
-- V6__org.sql —— v2.1 M3 线 F：组织隔离（02-技术方案 §B9，F10）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < zhixing-bootstrap/src/main/resources/db/V6__org.sql
-- ============================================================

-- 组织（部门形态，02 §B9 DDL；TIMESTAMP 从众 V4/V5 既有约定）
CREATE TABLE organization (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_organization_name ON organization(name);

-- 组织成员（role：ADMIN=组织管理员 / MEMBER=普通成员）
CREATE TABLE org_member (
    org_id      BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);
CREATE INDEX idx_org_member_user ON org_member(user_id);

-- 库归属组织（02 §B9 为 ADD COLUMN REFERENCES organization(id)；应用层维护引用，
-- 不加 DB 级 FK——组织删除/库删除的级联语义在 OrgService/KbService 收口，迁移幂等可重跑）
ALTER TABLE kb_knowledge_base ADD COLUMN org_id BIGINT;
CREATE INDEX idx_kb_org ON kb_knowledge_base(org_id);
