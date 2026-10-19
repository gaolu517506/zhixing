package com.zhixing.core.memory;

import cn.hutool.core.util.StrUtil;
import com.zhixing.config.SummaryProperties;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 滚动摘要器（详设 08-阶段2 §6）：旧摘要 + 新完成的轮次 → 新摘要（同步 call，DL-14 ②）。
 * 失败由调用方降级为"维持旧值"，本类只负责一次 roll。
 */
@Component
public class Summarizer {

    /** 历史行渲染时助手内容的截断长度（详设 §4.2 的 history 行格式约定）。 */
    private static final int TURN_CONTENT_MAX = 300;

    private final ChatClient chatClient;

    private final SummaryProperties props;

    private final String template;

    public Summarizer(ChatClient chatClient, SummaryProperties props) {
        this.chatClient = chatClient;
        this.props = props;
        this.template = loadTemplate();
    }

    private static String loadTemplate() {
        try {
            return new String(new ClassPathResource("prompts/summarize-v1.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompts/summarize-v1.md 缺失", e);
        }
    }

    /**
     * @param oldSummary 旧滚动摘要（可 null/空，渲染为占位说明）
     * @param turns      待摘的已完成轮次（升序展开的 user/assistant 序列）
     * @return 新摘要，超 maxChars 截断（模板规则要求 ≤200 字，截断是防线不是常规路径）
     */
    public String roll(String oldSummary, List<Turn> turns) {
        String prompt = template
                .replace("{old_summary}", StrUtil.isBlank(oldSummary) ? "（无）" : oldSummary)
                .replace("{turns}", renderTurns(turns));
        ChatResponse response = chatClient.call(ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.1)
                .maxTokens(256)
                .build());
        return truncateSummary(response.content());
    }

    /** 历史行格式：每轮「用户：{q}」/「助手：{a}」，助手侧截断 300 字（与重写 prompt 的 history 渲染同规）。 */
    private static String renderTurns(List<Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            String content = "assistant".equals(t.role())
                    ? truncateTurnContent(t.content())
                    : t.content();
            sb.append("assistant".equals(t.role()) ? "助手：" : "用户：").append(content);
            if (i < turns.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String truncateTurnContent(String text) {
        if (text == null || text.length() <= TURN_CONTENT_MAX) {
            return text;
        }
        return text.substring(0, TURN_CONTENT_MAX) + "…";
    }

    private String truncateSummary(String text) {
        int max = props.getMaxChars();
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
