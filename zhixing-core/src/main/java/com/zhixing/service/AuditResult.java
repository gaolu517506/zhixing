package com.zhixing.service;

/**
 * 审计事件 result 列取值（V4__auth.sql：OK / FAIL，02-技术方案 §B3）。
 */
public final class AuditResult {

    public static final String OK = "OK";

    public static final String FAIL = "FAIL";

    private AuditResult() {
    }
}
