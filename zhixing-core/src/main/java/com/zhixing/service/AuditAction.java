package com.zhixing.service;

/**
 * 审计事件枚举（V4__auth.sql 注释的闭合集合，02-技术方案 §B3）。action 列取值域唯一来源——
 * 新事件必须在此追加，不落枚举的字符串不得入库。
 *
 * <p>触发点属地：LOGIN、LOGOUT、KB_CREATE、KB_DELETE、DOC_UPLOAD、DOC_DELETE、
 * AGENT_CONFIRM_APPROVE/REJECT 在线 A（T6-0-A）接线；
 * TOKEN_ISSUE/TOKEN_REVOKE 属线 B（api_token）；USER_DISABLE/USER_ENABLE、EVAL_RUN、
 * PERMISSION_CHANGE 属管理后台/评测线（T6-0-D 接线）；
 * RESET_PASSWORD 属管理后台用户管理（v2.0 维护线 DL-29 扩枚举接线——高危凭据操作
 * 与 DISABLE/ENABLE 同级，审计完整性优先于枚举闭合性，02 §B3 已随 DL-29 勘误）。
 * ORG_CHANGE 属组织隔离线（T8-1 接线：组织成员增删改/库归属变更，detail 记 from→to）。
 */
public enum AuditAction {
    LOGIN,
    LOGOUT,
    KB_CREATE,
    KB_DELETE,
    DOC_UPLOAD,
    DOC_DELETE,
    AGENT_CONFIRM_APPROVE,
    AGENT_CONFIRM_REJECT,
    PERMISSION_CHANGE,
    TOKEN_ISSUE,
    TOKEN_REVOKE,
    USER_DISABLE,
    USER_ENABLE,
    EVAL_RUN,
    RESET_PASSWORD,
    ORG_CHANGE
}
