package com.zhixing.infra.llm.dto;

/**
 * 一次工具调用（Function Calling 协议，阶段 3 契约基线）。
 * arguments 为 JSON 字符串——流式下分片到达，由 ToolCallAggregator 聚合成完整串。
 */
public record ToolCall(String id, String name, String arguments) {
}
