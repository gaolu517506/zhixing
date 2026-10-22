package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.llm.dto.ToolCallDelta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * tool_calls 增量聚合器（阶段 3 契约基线）：同一 stream 内的碎片按 index 分桶，
 * id/name 取首片，arguments 逐片拼接；snapshot 按 index 升序输出完整 ToolCall 列表。
 *
 * <p>有状态类：每个流新建一个实例（对比无状态的 OpenAiStyleSseParser——
 * 解析是纯函数可全局复用，聚合是流内累积必须隔离）。
 */
public class ToolCallAggregator {

    private final Map<Integer, String> ids = new TreeMap<>();

    private final Map<Integer, String> names = new TreeMap<>();

    private final Map<Integer, StringBuilder> arguments = new TreeMap<>();

    public void feed(ToolCallDelta delta) {
        if (delta == null) {
            return;
        }
        int index = delta.index();
        if (delta.id() != null) {
            ids.put(index, delta.id());
        }
        if (delta.name() != null) {
            names.put(index, delta.name());
        }
        if (delta.argumentsFragment() != null) {
            arguments.computeIfAbsent(index, i -> new StringBuilder()).append(delta.argumentsFragment());
        }
    }

    /** 聚合快照。id 缺失（怪供应商）时以 call_{index} 兜底，保证 toolCallId 配对不悬空。 */
    public List<ToolCall> snapshot() {
        List<ToolCall> calls = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> entry : arguments.entrySet()) {
            int index = entry.getKey();
            calls.add(new ToolCall(
                    ids.getOrDefault(index, "call_" + index),
                    names.getOrDefault(index, ""),
                    entry.getValue().toString()));
        }
        return List.copyOf(calls);
    }
}
