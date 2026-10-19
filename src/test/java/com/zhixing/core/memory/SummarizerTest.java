package com.zhixing.core.memory;

import com.zhixing.config.SummaryProperties;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Summarizer 单测（任务书 §3）：桩 ChatClient，离线断言模板渲染、采样参数与截断。
 */
class SummarizerTest {

    static class FakeChat implements ChatClient {
        String content = "新摘要";
        ChatRequest lastRequest;

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            lastRequest = request;
            return new ChatResponse(content, "stop", null);
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            throw new UnsupportedOperationException("Summarizer 只走同步 call");
        }
    }

    private final FakeChat chat = new FakeChat();

    private final Summarizer summarizer = new Summarizer(chat, new SummaryProperties());

    @Test
    void 模板渲染_旧摘要与轮次注入且采样参数正确() {
        String out = summarizer.roll("旧摘要文本",
                List.of(new Turn("user", "q1"), new Turn("assistant", "a1")));

        assertThat(out).isEqualTo("新摘要");
        ChatRequest req = chat.lastRequest;
        assertThat(req.getTemperature()).isEqualTo(0.1);
        assertThat(req.getMaxTokens()).isEqualTo(256);
        String prompt = req.getMessages().get(0).content();
        assertThat(prompt).contains("旧摘要文本").contains("用户：q1").contains("助手：a1");
        assertThat(prompt).doesNotContain("{old_summary}").doesNotContain("{turns}");
    }

    @Test
    void 空旧摘要_渲染占位说明() {
        summarizer.roll(null, List.of(new Turn("user", "q")));

        assertThat(chat.lastRequest.getMessages().get(0).content()).contains("（无）");
    }

    @Test
    void 超maxChars截断到200() {
        chat.content = "字".repeat(250);

        assertThat(summarizer.roll("旧", List.of(new Turn("user", "q")))).hasSize(200);
    }

    @Test
    void 助手轮次内容截断300字() {
        summarizer.roll("旧", List.of(new Turn("assistant", "a".repeat(400))));

        String prompt = chat.lastRequest.getMessages().get(0).content();
        assertThat(prompt).contains("a".repeat(300) + "…");
        assertThat(prompt).doesNotContain("a".repeat(301));
    }
}
