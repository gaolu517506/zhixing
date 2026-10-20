package com.zhixing.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 问题重写（台账 T2-2，详设 08-阶段2 §4）。以 @Primary 覆盖契约基线的 {@link NoopQueryRewriter}。
 *
 * <p>容错链（DL-14 ③：重写失败降级不终态）：
 * 剥 ```json 围栏 → Jackson 解析 → 失败重试 1 次（追加提示）→ 再失败返回 rewritten=false；
 * 任何异常（含模型调用失败）同样降级——本类对调用方「绝不抛出」，可用性优先于质量。
 *
 * <p>成本纪律：history 与 summary 均为空（首轮）直接降级，不花 LLM 调用。
 */
@Slf4j
@Primary
@Component
public class LlmQueryRewriter implements QueryRewriter {

    /** 详设 §4.2：助手历史内容截断 300 字。 */
    private static final int ASSISTANT_SNIPPET_MAX = 300;

    /** 任务书 §2.1：keywords 上限 8（模板要求 3-6，超限裁剪）；与 PgTrgmSearch 的参数防御双保险。 */
    static final int KEYWORDS_MAX = 8;

    private static final String RETRY_HINT = "\n\n上一次输出不是合法 JSON，只输出 JSON。";

    /** ```json 围栏剥离：带/不带 json 标签、单行/多行均可。 */
    private static final Pattern FENCED = Pattern.compile("^```(?:json)?\\s*([\\s\\S]*?)\\s*```\\s*$");

    private final ChatClient chatClient;

    private final ObjectMapper objectMapper;

    private final String promptTemplate;

    public LlmQueryRewriter(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.promptTemplate = loadPromptTemplate();
    }

    private static String loadPromptTemplate() {
        try {
            return new String(new ClassPathResource("prompts/rewrite-v1.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompts/rewrite-v1.md 缺失", e);
        }
    }

    @Override
    public RewrittenQuery rewrite(List<Turn> history, String summary, String question) {
        if ((history == null || history.isEmpty()) && isBlank(summary)) {
            // 首轮（无历史且无摘要）不重写——省一次 LLM 调用
            return new RewrittenQuery(question, List.of(), false);
        }
        try {
            String prompt = render(history, summary, question);
            JsonNode parsed = parseInTwoAttempts(prompt);
            if (parsed == null) {
                log.warn("重写输出两次均非合法 JSON，降级原问题");
                return new RewrittenQuery(question, List.of(), false);
            }
            return toRewritten(parsed, question);
        } catch (Exception e) {
            // 任何调用异常同降级：本类对调用方绝不抛出
            log.warn("问题重写失败，降级原问题: {}", e.getMessage());
            return new RewrittenQuery(question, List.of(), false);
        }
    }

    /** 同步调用 + 解析，失败追加提示重试一次；两次都不可解析返回 null。 */
    private JsonNode parseInTwoAttempts(String prompt) {
        JsonNode parsed = parse(call(prompt));
        if (parsed == null) {
            parsed = parse(call(prompt + RETRY_HINT));
        }
        return parsed;
    }

    private String call(String prompt) {
        ChatResponse response = chatClient.call(ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.0)
                .maxTokens(512)
                .build());
        return response != null ? response.content() : null;
    }

    /** 围栏剥离 + Jackson 解析；内容为空或不可解析返回 null（不抛异常，由调用方决定重试/降级）。 */
    private JsonNode parse(String content) {
        if (isBlank(content)) {
            return null;
        }
        String candidate = stripFences(content.trim());
        try {
            JsonNode node = objectMapper.readTree(candidate);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String stripFences(String trimmed) {
        Matcher matcher = FENCED.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        // 只有开头围栏、缺结尾围栏的半截输出：剥掉首行 ```/```json 后按原样尝试
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                return trimmed.substring(firstNewline + 1).trim();
            }
        }
        return trimmed;
    }

    private RewrittenQuery toRewritten(JsonNode node, String question) {
        String standalone = node.path("standalone_question").asText("").trim();
        List<String> keywords = cleanKeywords(node.get("keywords"));
        if (standalone.isEmpty()) {
            // 解析成功但没有独立问题：原问题直查，关键词若产出仍可用
            return new RewrittenQuery(question, keywords, false);
        }
        return new RewrittenQuery(standalone, keywords, true);
    }

    /** 非字符串项剔除、去重、截到 8 个；全链路防御（模型输出不可信任）。 */
    static List<String> cleanKeywords(JsonNode keywordsNode) {
        if (keywordsNode == null || !keywordsNode.isArray()) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (JsonNode item : keywordsNode) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String keyword = item.asText().trim();
            if (!keyword.isEmpty()) {
                cleaned.add(keyword);
            }
            if (cleaned.size() >= KEYWORDS_MAX) {
                break;
            }
        }
        return List.copyOf(cleaned);
    }

    /** 渲染 rewrite-v1.md：摘要最前（【此前摘要】），历史每轮「用户：」「助手：」（助手截断 300 字）。 */
    String render(List<Turn> history, String summary, String question) {
        StringBuilder historyText = new StringBuilder();
        if (!isBlank(summary)) {
            historyText.append("【此前摘要】").append(summary.trim()).append('\n');
        }
        if (history != null) {
            for (Turn turn : history) {
                if (turn == null || isBlank(turn.content())) {
                    continue;
                }
                String role = turn.role() == null ? "" : turn.role();
                if ("user".equals(role)) {
                    historyText.append("用户：").append(turn.content()).append('\n');
                } else if ("assistant".equals(role)) {
                    historyText.append("助手：").append(truncate(turn.content(), ASSISTANT_SNIPPET_MAX)).append('\n');
                }
                // 契约外 role 防御性跳过
            }
        }
        return promptTemplate
                .replace("{history}", historyText.toString().stripTrailing())
                .replace("{question}", question);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
