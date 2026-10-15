package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatUsage;

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
     * （tool_calls 阶段 1 只透传不处理）；usage 可能为 null（供应商不回）。
     */
    void onComplete(String finishReason, ChatUsage usage);

    void onError(Throwable t);
}
