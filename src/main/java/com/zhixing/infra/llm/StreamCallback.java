package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.llm.dto.ToolCall;

import java.util.List;

/**
 * 流式回调。契约：onContent/onThinking 按到达顺序回调若干次后，
 * onComplete 与 onError 互斥且恰好回调其一（正常收尾 vs 失败）。
 * onThinking 只有推理模型才会触发，不关心的调用方零成本忽略（default 空实现）。
 */
public interface StreamCallback {

    void onContent(String delta);

    default void onThinking(String delta) {
    }

    /**
     * 流正常收尾。finishReason 原样透传 stop / length / tool_calls
     * （tool_calls 阶段 1 只透传不处理；阶段 3 起 finishReason=tool_calls 时
     * 先回调 {@link #onToolCalls} 再回调本方法，聚合结果即工具调用全集）；usage 可能为 null。
     */
    void onComplete(String finishReason, ChatUsage usage);

    /**
     * 工具调用聚合完成（阶段 3 契约：finishReason=tool_calls 的流，本回调在 onComplete 之前
     * 恰好触发一次；content 类流不触发）。default 空实现保阶段 1 调用方零改动。
     */
    default void onToolCalls(List<ToolCall> calls) {
    }

    void onError(Throwable t);
}
