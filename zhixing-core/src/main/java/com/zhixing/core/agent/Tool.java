package com.zhixing.core.agent;

import java.util.Map;

/**
 * Agent 工具契约（阶段 3 契约基线，详设 13-阶段3 §6）。
 * A 线的 ToolRegistry 收集全部 Tool bean 自动注册；B 线的三个工具实现放 service/agenttools
 * （delete_document 需要依赖 service 层的级联删除能力，故实现统一在 service 层）。
 */
public interface Tool {

    /** 工具名（Function Calling 的 function.name，蛇形命名）。 */
    String name();

    /** 面向模型的工具描述（决定模型何时选择本工具，写清适用场景与边界）。 */
    String description();

    /** 参数 JSON Schema 文本（协议序列化时内嵌为 parameters 字段）。 */
    String parametersSchema();

    /**
     * 执行工具。实现方不自行捕获业务异常——由 ToolExecutor 统一转错误结果回填让模型自纠；
     * 参数已按 schema 解析为 Map。
     */
    ToolResult execute(Map<String, Object> args);

    /** true = 写操作，执行前必经人工确认（ConfirmManager 据此分流，不靠命名约定）。 */
    default boolean requireConfirm() {
        return false;
    }
}
