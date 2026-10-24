package com.zhixing.infra.llm.dto;

import java.util.List;

/**
 * 对话消息。role 取 system / user / assistant / tool。
 * 阶段 3 扩展（Function Calling）：assistant 消息可携带 toolCalls（发起的工具调用）；
 * role="tool" 的结果消息必须携带 toolCallId（与调用 id 配对，供应商强校验顺序与配对）。
 * 既有静态工厂保持兼容（content-only 形态）。
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** assistant 消息携带工具调用（content 为 null）。 */
    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, List.copyOf(toolCalls), null);
    }

    /** 工具结果消息（role="tool"，toolCallId 与调用配对）。 */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }
}
