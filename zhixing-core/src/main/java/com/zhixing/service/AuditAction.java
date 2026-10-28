package com.zhixing.service;

/**
 * 审计事件枚举（V4__auth.sql 注释的闭合集合，02-技术方案 §B3）。action 列取值域唯一来源——
 * 新事件必须在此追加，不落枚举的字符串不得入库。
 *
 * <p>触发点属地：LOGIN、LOGOUT、KB_CREATE、KB_DELETE、DOC_UPLOAD、DOC_DELETE、
 * AGENT_CONFIRM_APPROVE/REJECT 在本线（T6-0-A）接线；
 * TOKEN_ISSUE/TOKEN_REVOKE 属线 B（api_token）；USER_DISABLE/USER_ENABLE、EVAL_RUN、
 * PERMISSION_CHANGE 属管理后台/评测线（02 §B6/B7 端点落地时接线）——枚举先闭合，
 * 各线只管调用，取值域不漂移。
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
    EVAL_RUN
}
