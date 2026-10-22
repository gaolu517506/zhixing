package com.zhixing.infra.llm.dto;

import com.zhixing.infra.llm.dto.ToolDefinition;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * 对话请求（供应商无关）。采样参数全部可空，为空时走供应商默认值。
 * stream 字段仅为与 OpenAI 协议对齐而保留，实际同步/流式由调用 call() 还是 stream() 决定。
 */
@Value
@Builder
public class ChatRequest {

    /** 模型名；null 时用该供应商 yaml 里配的默认 chat-model。 */
    String model;

    @Singular
    List<ChatMessage> messages;

    Double temperature;

    Double topP;

    Integer maxTokens;

    @Builder.Default
    boolean stream = false;

    /** 工具声明（Function Calling，阶段 3）；null/空 = 不带工具，阶段 1/2 行为不变。 */
    @Singular
    List<ToolDefinition> tools;
}
