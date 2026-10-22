package com.zhixing.infra.llm.dto;

/**
 * SSE 流中单个 tool_calls 增量碎片（阶段 3 契约基线）。
 * OpenAI 协议：id/name 只在首片出现，arguments 跨多个 chunk 逐片追加——
 * 由 ToolCallAggregator 按 index 分桶聚合。
 */
public record ToolCallDelta(int index, String id, String name, String argumentsFragment) {
}
