package com.zhixing.core.agent;

/**
 * 挂起的写工具调用（agent_run.pending_tool JSONB 的载体，详设 13-阶段3 §8）。
 */
public record PendingToolCall(String toolCallId, String name, String arguments) {
}
