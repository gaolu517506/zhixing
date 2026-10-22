package com.zhixing.core.agent;

/**
 * 工具执行结果（阶段 3 契约基线）。success=false 时 content 为面向模型的错误说明
 * （回填后让模型自纠），error 为技术细节（只进日志与 step 落库）。
 */
public record ToolResult(boolean success, String content, String error) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult fail(String contentForModel, String technicalError) {
        return new ToolResult(false, contentForModel, technicalError);
    }
}
