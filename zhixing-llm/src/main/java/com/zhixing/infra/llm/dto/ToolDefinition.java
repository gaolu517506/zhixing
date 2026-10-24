package com.zhixing.infra.llm.dto;

/**
 * 工具声明（ChatRequest.tools 元素，Function Calling 协议）。
 * parametersSchema 为 JSON Schema 文本，序列化时内嵌为 parameters 字段。
 */
public record ToolDefinition(String name, String description, String parametersSchema) {
}
