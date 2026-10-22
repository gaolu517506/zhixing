package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ToolCallDelta;
import com.zhixing.infra.llm.dto.ChatUsage;

import java.util.List;

/**
 * SSE 单行的解析产物。纯数据载体，除 done 外全字段可空，调用方按 null 判断消费。
 * 一行同时携带多种信息（如最后一个 content chunk 附带 finish_reason）时字段并存。
 * 阶段 3：toolCallDeltas 非空表示本行携带 tool_calls 增量碎片（ToolCallAggregator 聚合）。
 */
public record ParsedEvent(
        String content,
        String reasoning,
        String finishReason,
        ChatUsage usage,
        boolean done,
        String errorMessage,
        List<ToolCallDelta> toolCallDeltas) {

    public static final ParsedEvent EMPTY = new ParsedEvent(null, null, null, null, false, null, null);

    public static final ParsedEvent DONE = new ParsedEvent(null, null, null, null, true, null, null);

    public static ParsedEvent ofUsage(ChatUsage usage) {
        return new ParsedEvent(null, null, null, usage, false, null, null);
    }

    public static ParsedEvent ofError(String errorMessage) {
        return new ParsedEvent(null, null, null, null, false, errorMessage, null);
    }

    public static ParsedEvent ofToolDeltas(List<ToolCallDelta> deltas) {
        return new ParsedEvent(null, null, null, null, false, null, List.copyOf(deltas));
    }
}
