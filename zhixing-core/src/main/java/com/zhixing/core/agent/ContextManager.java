package com.zhixing.core.agent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.AgentProperties;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 上下文水位管理（详设 13-阶段3 §7，DL-15 ①全量实现）：
 * ① 超限先裁剪最早的**可驱逐工具**结果（content 换占位文本，tool 消息本身与 toolCallId
 *    配对保留——供应商强校验 tool 消息存在，只能截内容不能删消息）；
 * ② 仍超限 → LLM 摘要压缩最早的**非系统、content 非空**消息（复用 Summarizer 模式）。
 * 全程降级不终态：治理失败只告警，按原 messages 继续（超限是质量问题不是故障）。
 */
@Slf4j
@Component
public class ContextManager {

    /** 可驱逐白名单：检索结果是可再生的（再问一次即可），白名单外的结果不动。 */
    static final Set<String> EVICTABLE_TOOLS = Set.of("search_knowledge");

    private static final String TRUNCATED_MARK = "[已截断：tool result 原始 %d 字符]";

    private final AgentProperties props;

    private final ChatClient chatClient;

    private final String compressTemplate;

    public ContextManager(AgentProperties props, ChatClient chatClient) {
        this.props = props;
        this.chatClient = chatClient;
        this.compressTemplate = loadTemplate();
    }

    private static String loadTemplate() {
        try {
            return new String(new ClassPathResource("prompts/context-compress-v1.md").getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompts/context-compress-v1.md 缺失", e);
        }
    }

    /** 每轮迭代入口调用。原位修改 messages。 */
    public void enforce(List<ChatMessage> messages) {
        try {
            if (watermark(messages) <= props.getMaxContextChars()) {
                return;
            }
            log.info("上下文水位超限（{} 字符），尝试裁剪最早可驱逐工具结果", watermark(messages));
            if (trimOldestEvictableToolResult(messages)
                    && watermark(messages) <= props.getMaxContextChars()) {
                return;
            }
            log.info("裁剪后仍超限（{} 字符），LLM 摘要压缩最早非系统消息", watermark(messages));
            compressOldestNonSystemMessage(messages);
        } catch (Exception e) {
            log.warn("上下文水位治理失败（降级不终态，按原样继续）: {}", e.getMessage());
        }
    }

    /** messages 全部 content 与 toolCalls arguments 的字符数之和。 */
    long watermark(List<ChatMessage> messages) {
        long sum = 0;
        for (ChatMessage m : messages) {
            if (m.content() != null) {
                sum += m.content().length();
            }
            if (m.toolCalls() != null) {
                for (com.zhixing.infra.llm.dto.ToolCall c : m.toolCalls()) {
                    sum += c.arguments() == null ? 0 : c.arguments().length();
                }
            }
        }
        return sum;
    }

    /**
     * 裁剪最早一条可驱逐工具的未截断结果。工具名经由 assistant(toolCalls) 消息反查
     * （tool 消息只带 toolCallId，不带名字）。找到并替换返回 true。
     */
    boolean trimOldestEvictableToolResult(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (!"tool".equals(m.role()) || m.content() == null
                    || m.content().startsWith("[已截断")) {
                continue;
            }
            String toolName = toolNameOf(messages, m.toolCallId());
            if (EVICTABLE_TOOLS.contains(toolName)) {
                int original = m.content().length();
                messages.set(i, ChatMessage.toolResult(m.toolCallId(),
                        TRUNCATED_MARK.formatted(original)));
                return true;
            }
        }
        return false;
    }

    /** LLM 摘要压缩最早一条非系统、content 非空的消息（原位替换，role/toolCalls 结构保留）。 */
    void compressOldestNonSystemMessage(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if ("system".equals(m.role()) || StrUtil.isBlank(m.content())) {
                continue;
            }
            String prompt = compressTemplate.replace("{content}", m.content());
            String summary = chatClient.call(ChatRequest.builder()
                            .messages(List.of(ChatMessage.user(prompt)))
                            .temperature(0.1)
                            .maxTokens(256)
                            .build())
                    .content();
            if (StrUtil.isBlank(summary)) {
                log.warn("上下文压缩返回空，保持原消息");
                return;
            }
            messages.set(i, new ChatMessage(m.role(), summary, m.toolCalls(), m.toolCallId()));
            return;
        }
    }

    /** 反查 toolCallId 所属工具名（遍历 assistant toolCalls 消息）；查不到返回 null。 */
    private static String toolNameOf(List<ChatMessage> messages, String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        for (ChatMessage m : messages) {
            if (m.toolCalls() == null) {
                continue;
            }
            for (com.zhixing.infra.llm.dto.ToolCall c : m.toolCalls()) {
                if (toolCallId.equals(c.id())) {
                    return c.name();
                }
            }
        }
        return null;
    }
}
