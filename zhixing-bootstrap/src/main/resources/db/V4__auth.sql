-- ============================================================
-- V4__auth.sql —— v2.0 M1 认证 / 权限 / 审计 / API Token
-- 来源：02-技术方案 §A7 数据模型 + §B1-B5 详细设计（T6-0 契约基线，DL-24）
-- 执行方式：docker exec -i zhixing-pg psql -U postgres -d zhixing < zhixing-bootstrap/src/main/resources/db/V4__auth.sql
-- ============================================================

-- 用户（F1：BCrypt 哈希；首启向导创建首个 ADMIN，空表时 /api/auth/setup 才可用）
CREATE TABLE auth_user (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / DISABLED（禁用即失效，存量会话下次请求被拒）
    system_role   VARCHAR(16) NOT NULL DEFAULT 'USER',     -- ADMIN / USER
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- 服务端会话（opaque token：明文仅登录响应返回一次，库存 SHA-256——可吊销、可审计，D13）
CREATE TABLE auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_session_user ON auth_session(user_id);

-- 库可见性与成员（F2：KB 级 ACL；判定单点 PermissionService，controller 零权限逻辑 D14）
ALTER TABLE kb_knowledge_base ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';  -- PRIVATE / ALL
CREATE TABLE kb_member (
    kb_id      BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    role       VARCHAR(16) NOT NULL DEFAULT 'MEMBER',      -- ADMIN / MEMBER（库角色，两级）
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (kb_id, user_id)
);

-- 操作审计（F3：仅追加——dao 层不提供 update/delete；actor_name 冗余防用户改名后审计漂移，D15 显式调用）
CREATE TABLE audit_event (
    id          BIGINT PRIMARY KEY,
    actor_id    BIGINT,
    actor_name  VARCHAR(64),
    action      VARCHAR(32) NOT NULL,   -- LOGIN/LOGOUT/KB_CREATE/KB_DELETE/DOC_UPLOAD/DOC_DELETE/AGENT_CONFIRM_APPROVE/AGENT_CONFIRM_REJECT/PERMISSION_CHANGE/TOKEN_ISSUE/TOKEN_REVOKE/USER_DISABLE/USER_ENABLE/EVAL_RUN
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    result      VARCHAR(8) NOT NULL,    -- OK / FAIL
    detail      JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor ON audit_event(actor_id, created_at);
CREATE INDEX idx_audit_action ON audit_event(action, created_at);

-- 开放 API token（F5：明文格式 zxk_<random>；归属真实用户 + 渠道标记，与前端同一权限模型，D16）
CREATE TABLE api_token (
    id            BIGINT PRIMARY KEY,
    name          VARCHAR(64) NOT NULL,
    token_hash    CHAR(64) NOT NULL UNIQUE,
    owner_user_id BIGINT NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / REVOKED
    daily_quota   INT NOT NULL DEFAULT 1000,
    used_today    INT NOT NULL DEFAULT 0,
    used_date     DATE NOT NULL,
    expires_at    TIMESTAMP,
    last_used_at  TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- 提问与 Agent 运行补操作者维度（审计「谁问了什么/谁执行了什么」的数据基础；存量行置空可接受——
-- 历史归历史，审计事件从 v2.0 起全量落 actor）
ALTER TABLE qa_session ADD COLUMN user_id BIGINT;
ALTER TABLE agent_run ADD COLUMN user_id BIGINT;
