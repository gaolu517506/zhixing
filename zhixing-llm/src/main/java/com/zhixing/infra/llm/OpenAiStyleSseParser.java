package com.zhixing.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.infra.llm.dto.ToolCallDelta;
import com.zhixing.infra.llm.dto.ChatUsage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 协议风格 SSE 流的逐行解析器。
 *
 * 设计约束：纯函数、无状态、任何输入都不抛异常——LLM 的真实流无法在单测里重现，
 * 但解析逻辑可以离线穷举，所以坏行按容错规则 1 降级为 EMPTY（warn 后跳过），绝不打断整个流。
 * 全类不持有任何请求/连接上下文，同一实例可被并发复用。
 */
@Slf4j
public class OpenAiStyleSseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DATA_PREFIX = "data:";

    private static final int LOG_SNIPPET_LENGTH = 200;

    public ParsedEvent parseLine(String line) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.EMPTY;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith(":")) {
            return ParsedEvent.EMPTY;               // SSE 注释/心跳行
        }
        if (trimmed.startsWith(DATA_PREFIX)) {
            return parseData(trimmed.substring(DATA_PREFIX.length()));
        }
        if (trimmed.startsWith("{")) {
            // 容错规则 3 的入口之一：请求流式却收到 200+JSON 错误体（不是 SSE 格式），
            // 整行没有 data: 前缀，同样要能识别成失败而不是静默丢弃
            return parseData(trimmed);
        }
        return ParsedEvent.EMPTY;                   // event:/id:/retry: 等控制行，忽略
    }

    private ParsedEvent parseData(String payload) {
        if (payload.startsWith(" ")) {
            payload = payload.substring(1);         // "data: x" 与 "data:x" 两种形态都要兼容
        }
        if (payload.isEmpty()) {
            return ParsedEvent.EMPTY;
        }
        if ("[DONE]".equals(payload.trim())) {
            return ParsedEvent.DONE;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            // 容错规则 1：单行解析失败 → warn 跳过，不断流（宁丢一个 token 不断整个流）
            log.warn("SSE 行 JSON 解析失败，跳过该行：{}", snippet(payload));
            return ParsedEvent.EMPTY;
        }
        if (!node.isObject()) {
            return ParsedEvent.EMPTY;
        }

        // 容错规则 3：data 里带 error 对象 → 必须当失败，防把错误当成功；
        // v1.1（S2）：按 error 对象内容细分类型，鉴权/限流不再伪装成可降级的 SERVER_ERROR
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            String message = error.isObject()
                    ? error.path("message").asText(error.toString())
                    : error.asText();
            return ParsedEvent.ofError(message, classifyErrorBody(error));
        }

        ChatUsage usage = ChatUsage.fromJson(node.get("usage"));
        JsonNode choice = node.path("choices").path(0);
        if (choice.isMissingNode() || !choice.isObject()) {
            // 无 choices：典型是 stream_options.include_usage 的最终 usage 块（choices 为空数组）
            return usage != null ? ParsedEvent.ofUsage(usage) : ParsedEvent.EMPTY;
        }

        JsonNode delta = choice.path("delta");
        String content = delta.path("content").asText(null);
        String reasoning = delta.path("reasoning_content").asText(null);
        String finishReason = choice.path("finish_reason").asText(null);

        // 阶段 3：delta.tool_calls 增量碎片（id/name 首片、arguments 逐片），由聚合器组装
        List<ToolCallDelta> toolDeltas = null;
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            toolDeltas = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                toolDeltas.add(new ToolCallDelta(
                        tc.path("index").asInt(0),
                        tc.path("id").asText(null),
                        tc.path("function").path("name").asText(null),
                        tc.path("function").path("arguments").asText(null)));
            }
        }

        if (content == null && reasoning == null && finishReason == null && usage == null && toolDeltas == null) {
            return ParsedEvent.EMPTY;               // 首 chunk 只有 role、空 delta 等
        }
        if (toolDeltas != null) {
            return ParsedEvent.ofToolDeltas(toolDeltas);
        }
        return new ParsedEvent(content, reasoning, finishReason, usage, false, null, null, null);
    }

    /**
     * error body 分类（S2，语义参照 embedding 线 switchable 的分类思想；chat 侧切不切换
     * 仍由 ErrorType.fallbackCapable 决定，共享枚举既有取值不动）：扫 code/type/status 三个
     * 结构化字段——401/403 及鉴权字样 → UNAUTHORIZED，429 及限流字样 → RATE_LIMITED，
     * 其余（含余额类 code 如 1113）→ SERVER_ERROR。纯函数，流式与同步 200+error 两路共用。
     */
    static ErrorType classifyErrorBody(JsonNode error) {
        if (error == null || !error.isObject()) {
            return ErrorType.SERVER_ERROR;
        }
        StringBuilder signals = new StringBuilder();
        for (String field : new String[]{"code", "type", "status"}) {
            JsonNode value = error.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                signals.append(value.asText()).append(' ');
            }
        }
        String s = signals.toString().toLowerCase();
        if (s.matches(".*(^|[^0-9])(401|403)([^0-9]|$).*") || s.contains("unauthorized")
                || s.contains("authentication") || s.contains("invalid_api_key")
                || s.contains("api key") || s.contains("permission")) {
            return ErrorType.UNAUTHORIZED;
        }
        if (s.matches(".*(^|[^0-9])429([^0-9]|$).*") || s.contains("rate_limit") || s.contains("ratelimit")
                || s.contains("rate limit") || s.contains("too_many_requests")
                || s.contains("too many requests") || s.contains("throttl")) {
            return ErrorType.RATE_LIMITED;
        }
        return ErrorType.SERVER_ERROR;
    }

    private static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= LOG_SNIPPET_LENGTH ? flat : flat.substring(0, LOG_SNIPPET_LENGTH) + "...";
    }
}
