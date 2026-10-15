package com.zhixing.infra.llm.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * token 用量。供应商可能不回（此时两个分量与解析结果均为 null），调用方必须容忍。
 */
public record ChatUsage(Integer promptTokens, Integer completionTokens) {

    /** 从 chunk 的 usage 节点提取；节点缺失或不是对象时返回 null。 */
    public static ChatUsage fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new ChatUsage(intOrNull(node.get("prompt_tokens")), intOrNull(node.get("completion_tokens")));
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }
}
