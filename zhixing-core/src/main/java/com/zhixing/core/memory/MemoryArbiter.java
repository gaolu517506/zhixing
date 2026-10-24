package com.zhixing.core.memory;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.MemoryProperties;
import com.zhixing.dao.entity.AgentMemory;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 记忆仲裁器（详设 14-v1.0 §3.3）：ACTIVE 记忆 + 本轮 turns → LLM 同步 call（temperature=0.0，
 * maxTokens=512）→ 操作列表。异步性由调用方包装（memory-pool）。
 *
 * <p>容错链同重写器：围栏剥离 → Jackson 解析 → 坏 JSON 重试一次（附纠正提示）→ 再败返回
 * **空操作列表**（静默放弃本轮仲裁——记忆缺失可容忍，绝不抛出影响主链路）。
 * 非法 action / targetId 不存在的 SUPERSEDE·RETRACT 逐条剔除。
 */
@Slf4j
@Component
public class MemoryArbiter {

    private final ChatClient chatClient;

    private final MemoryProperties props;

    private final ObjectMapper objectMapper;

    private final String template;

    public MemoryArbiter(ChatClient chatClient, MemoryProperties props, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.props = props;
        this.objectMapper = objectMapper;
        this.template = loadTemplate();
    }

    private static String loadTemplate() {
        try {
            return new String(new ClassPathResource("prompts/memory-arbiter-v1.md").getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompts/memory-arbiter-v1.md 缺失", e);
        }
    }

    /** 仲裁。永不抛出：解析两连败返回空列表。 */
    public List<MemoryOp> arbitrate(List<AgentMemory> active, List<Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        String prompt = template
                .replace("{active_memories}", renderActive(active))
                .replace("{turns}", renderTurns(turns));

        List<MemoryOp> ops = tryParse(prompt, null);
        if (ops == null) {
            // 坏 JSON 重试一次（附纠正提示），再败静默放弃本轮仲裁
            ops = tryParse(prompt, "\n\n注意：上一次输出不是合法 JSON。只输出 JSON 数组，不要任何其他文字。");
        }
        if (ops == null) {
            log.warn("记忆仲裁输出两次非法 JSON，静默放弃本轮仲裁");
            return List.of();
        }
        return sanitize(ops, active);
    }

    /** 一次解析尝试；返回 null 表示非法（调用方决定重试/放弃）。 */
    private List<MemoryOp> tryParse(String prompt, String retryHint) {
        try {
            String output = chatClient.call(ChatRequest.builder()
                            .messages(List.of(ChatMessage.user(retryHint == null ? prompt : prompt + retryHint)))
                            .temperature(0.0)
                            .maxTokens(512)
                            .build())
                    .content();
            String json = extractJsonArray(output);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<List<MemoryOp>>() {
            });
        } catch (Exception e) {
            log.debug("仲裁输出解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 非法 action / targetId 不在 ACTIVE 集合内的 SUPERSEDE·RETRACT 剔除。 */
    private List<MemoryOp> sanitize(List<MemoryOp> ops, List<AgentMemory> active) {
        Set<Long> activeIds = active.stream().map(AgentMemory::getId).collect(Collectors.toSet());
        List<MemoryOp> valid = new ArrayList<>();
        for (MemoryOp op : ops) {
            String action = op.action() == null ? "" : op.action().trim().toUpperCase();
            switch (action) {
                case MemoryOp.ADD -> {
                    if (StrUtil.isNotBlank(op.content())) {
                        valid.add(new MemoryOp(MemoryOp.ADD, null, op.content().trim()));
                    }
                }
                case MemoryOp.SUPERSEDE -> {
                    if (op.targetId() != null && activeIds.contains(op.targetId())
                            && StrUtil.isNotBlank(op.content())) {
                        valid.add(new MemoryOp(MemoryOp.SUPERSEDE, op.targetId(), op.content().trim()));
                    }
                }
                case MemoryOp.RETRACT -> {
                    if (op.targetId() != null && activeIds.contains(op.targetId())) {
                        valid.add(new MemoryOp(MemoryOp.RETRACT, op.targetId(), null));
                    }
                }
                default -> log.warn("记忆仲裁剔除非法操作: action={}", op.action());
            }
        }
        return List.copyOf(valid);
    }

    /** 剥围栏后取首个 '[' 到最后一个 ']' 的片段；不含数组则 null。 */
    private static String extractJsonArray(String output) {
        if (StrUtil.isBlank(output)) {
            return null;
        }
        String text = output.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline >= 0 ? text.substring(firstNewline + 1) : text.substring(3);
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String renderActive(List<AgentMemory> active) {
        if (active == null || active.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentMemory m : active) {
            sb.append("id=").append(m.getId()).append("：").append(m.getContent()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 历史行格式与 Summarizer 同规：每轮「用户：{q}」/「助手：{a}」，助手侧截断 300 字。 */
    private static String renderTurns(List<Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            String content = "assistant".equals(t.role()) ? preview(t.content()) : t.content();
            sb.append("assistant".equals(t.role()) ? "助手：" : "用户：").append(content);
            if (i < turns.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String preview(String text) {
        if (text == null || text.length() <= 300) {
            return text;
        }
        return text.substring(0, 300) + "…";
    }
}
