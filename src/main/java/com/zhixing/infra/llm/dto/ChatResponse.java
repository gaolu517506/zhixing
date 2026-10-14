package com.zhixing.infra.llm.dto;

/**
 * 同步对话响应。finishReason 原样透传 stop / length / tool_calls；usage 可能为 null。
 */
public record ChatResponse(String content, String finishReason, ChatUsage usage) {
}
