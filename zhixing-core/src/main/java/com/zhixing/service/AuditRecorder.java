package com.zhixing.service;

/**
 * 操作审计记录器（T6-0 契约基线，D15：显式调用，非 AOP）。
 *
 * <p>实现要求：记录失败只 warn 绝不抛出——审计不允许阻塞主链路；
 * 落库仅追加（dao 不提供 update/delete）。事件枚举与表结构见 V4__auth.sql 注释。
 */
public interface AuditRecorder {

    /**
     * 记录一条审计事件。
     *
     * @param actorId    操作者用户 ID（系统动作为 null）
     * @param actorName  操作者用户名冗余（防改名后审计漂移）
     * @param action     事件类型（LOGIN/KB_CREATE/DOC_UPLOAD/…，见 V4 注释枚举）
     * @param targetType 目标类型（KB/DOCUMENT/AGENT_RUN/TOKEN/USER/EVAL…，可空）
     * @param targetId   目标 ID 字符串化（可空）
     * @param result     OK / FAIL
     * @param detailJson 附加信息 JSON 字符串（可空，如失败原因、确认的工具名）
     */
    void record(Long actorId, String actorName, String action,
                String targetType, String targetId, String result, String detailJson);
}
