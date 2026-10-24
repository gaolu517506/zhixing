package com.zhixing.core.agent;

import com.zhixing.config.AgentProperties;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 上下文水位管理单测（任务书 §4）：裁剪分支 / 摘要分支 / 摘要失败降级不终态。
 * 全离线，ChatClient 用桩。
 */
class ContextManagerTest {

    static class StubChat implements ChatClient {
        String summary = "压缩后的摘要";
        RuntimeException failure;
        int calls;

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(summary, "stop", null);
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            throw new UnsupportedOperationException();
        }
    }

    private StubChat chat;

    private AgentProperties props;

    private ContextManager manager;

    @BeforeEach
    void setUp() {
        chat = new StubChat();
        props = new AgentProperties();
        props.setMaxContextChars(100);
        manager = new ContextManager(props, chat);
    }

    private static ChatMessage toolResult(String callId, String content) {
        return ChatMessage.toolResult(callId, content);
    }

    private static ChatMessage assistantWithCall(String callId, String name, String args) {
        return ChatMessage.assistantWithToolCalls(
                List.of(new com.zhixing.infra.llm.dto.ToolCall(callId, name, args)));
    }

    @Test
    void 超限_裁剪最早可驱逐工具结果_占位替换且配对保留() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("系统规则"),
                ChatMessage.user("问题"),
                assistantWithCall("c1", "search_knowledge", "{\"query\":\"K80\"}"),
                toolResult("c1", "检".repeat(200))));

        manager.enforce(messages);

        // content 被占位替换；tool 消息本身与 toolCallId 配对保留（供应商强校验）
        assertThat(messages.get(3).role()).isEqualTo("tool");
        assertThat(messages.get(3).toolCallId()).isEqualTo("c1");
        assertThat(messages.get(3).content())
                .isEqualTo("[已截断：tool result 原始 200 字符]");
        assertThat(manager.watermark(messages)).isLessThanOrEqualTo(100);
        assertThat(chat.calls).isZero();   // 裁剪已够，不触发 LLM 摘要
    }

    @Test
    void 白名单外工具结果不裁剪_直接进摘要分支() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("系统规则"),
                ChatMessage.user("问题"),
                assistantWithCall("c1", "delete_document", "{\"documentId\":1}"),
                toolResult("c1", "删".repeat(200)),
                ChatMessage.user("追".repeat(50))));
        chat.summary = "被压缩的早期内容";

        manager.enforce(messages);

        // delete_document 不在可驱逐白名单 → tool 结果原样保留
        assertThat(messages.get(3).content()).startsWith("删");
        // 摘要分支：最早非系统消息（index 1 的 user）被压缩
        assertThat(messages.get(1).content()).isEqualTo("被压缩的早期内容");
        assertThat(chat.calls).isEqualTo(1);
    }

    @Test
    void 裁剪不够_升级LLM摘要压缩最早非系统消息() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("系统规则"),
                ChatMessage.user("早".repeat(300)),
                assistantWithCall("c1", "search_knowledge", "{\"query\":\"x\"}"),
                toolResult("c1", "短结果")));
        // 裁剪 tool 结果省不了多少（结果本来就短），水位仍超 → 摘要压缩最早的 user
        props.setMaxContextChars(20);
        chat.summary = "压缩摘要";

        manager.enforce(messages);

        assertThat(messages.get(1).content()).isEqualTo("压缩摘要");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(chat.calls).isEqualTo(1);
    }

    @Test
    void 摘要调用失败_降级不终态_原消息保留() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("系统规则"),
                ChatMessage.user("早".repeat(300))));
        chat.failure = new IllegalStateException("llm down");

        assertThatCode(() -> manager.enforce(messages)).doesNotThrowAnyException();
        assertThat(messages.get(1).content()).hasSize(300);
    }

    @Test
    void 未超限_零动作() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("系统规则"),
                ChatMessage.user("短问题")));

        manager.enforce(messages);

        assertThat(messages.get(1).content()).isEqualTo("短问题");
        assertThat(chat.calls).isZero();
    }
}
