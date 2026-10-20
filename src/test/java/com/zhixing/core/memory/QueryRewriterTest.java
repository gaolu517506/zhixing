package com.zhixing.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问题重写离线单测（任务书 §3，详设 08-阶段2 §4.2/§4.4）：
 * prompt 渲染快照 / 合法 JSON / 围栏剥离 / 怪响应 / 坏 JSON 重试一次再败降级 / keywords 清洗 /
 * 空历史直接降级且不调 LLM。桩 ChatClient 固定行为，全程不联网。
 */
class QueryRewriterTest {

    /** 固定行为桩：记录收到的 prompt；响应 content 按队列出队（LinkedList 允许 null=怪响应）。 */
    static class StubChatClient implements ChatClient {
        int calls;
        final List<String> prompts = new java.util.ArrayList<>();
        final Queue<String> contents = new LinkedList<>(List.of(
                "{\"standalone_question\":\"Redmi K80 的防水等级是多少\",\"keywords\":[\"Redmi K80\",\"防水\",\"IP68\"]}"));
        RuntimeException failure;

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            calls++;
            prompts.add(request.getMessages().get(0).content());
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(contents.poll(), "stop", new ChatUsage(10, 5));
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            throw new UnsupportedOperationException("重写走同步 call，不该用 stream");
        }
    }

    private static final String QUESTION = "那 K80 呢";

    private StubChatClient stub;

    private LlmQueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        stub = new StubChatClient();
        rewriter = new LlmQueryRewriter(stub, new ObjectMapper());
    }

    private static List<Turn> defaultHistory() {
        return List.of(new Turn("user", "15 Ultra 防水吗"),
                new Turn("assistant", "小米15 Ultra 支持 IP68 级防尘防水。"));
    }

    @Test
    void 空历史且无摘要_直接降级且零LLM调用() {
        RewrittenQuery result = rewriter.rewrite(List.of(), null, QUESTION);
        assertThat(result).isEqualTo(new RewrittenQuery(QUESTION, List.of(), false));
        assertThat(stub.calls).isZero();

        // history 为 null、summary 为空白同样命中首轮短路
        assertThat(rewriter.rewrite(null, "   ", QUESTION).rewritten()).isFalse();
        assertThat(stub.calls).isZero();
    }

    @Test
    void prompt渲染_摘要最前_历史行格式_助手截断三百字() {
        String longAssistant = "甲".repeat(500);
        List<Turn> history = List.of(new Turn("user", "15 Ultra 防水吗"), new Turn("assistant", longAssistant));

        rewriter.rewrite(history, "用户在对比旗舰机的防水能力", QUESTION);

        String prompt = stub.prompts.get(0);
        // 摘要注入在历史块最前
        assertThat(prompt).contains("【此前摘要】用户在对比旗舰机的防水能力\n用户：15 Ultra 防水吗\n");
        // 历史行格式
        assertThat(prompt).contains("\n用户：15 Ultra 防水吗\n");
        String assistantLine = prompt.lines().filter(l -> l.startsWith("助手：")).findFirst().orElseThrow();
        assertThat(assistantLine).isEqualTo("助手：" + "甲".repeat(300));
        // 最新问题与模板尾部完整
        assertThat(prompt).contains("【最新问题】\n" + QUESTION);
        assertThat(prompt).contains("只输出 JSON（不要其他文字）：");
    }

    @Test
    void 合法JSON_解析成功() {
        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result.rewritten()).isTrue();
        assertThat(result.standaloneQuestion()).isEqualTo("Redmi K80 的防水等级是多少");
        assertThat(result.keywords()).containsExactly("Redmi K80", "防水", "IP68");
        assertThat(stub.calls).isEqualTo(1);
    }

    @Test
    void 围栏剥离_json标签与无标签均可() {
        stub.contents.clear();
        stub.contents.add("```json\n{\"standalone_question\":\"问题A\",\"keywords\":[\"k1\"]}\n```");
        assertThat(rewriter.rewrite(defaultHistory(), null, QUESTION).standaloneQuestion()).isEqualTo("问题A");

        stub.contents.add("```\n{\"standalone_question\":\"问题B\",\"keywords\":[]}\n```");
        assertThat(rewriter.rewrite(defaultHistory(), null, QUESTION).standaloneQuestion()).isEqualTo("问题B");
        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    void 怪响应_content为null_重试后仍降级() {
        // 相当于"首 token 只有 role"的空内容怪响应：两次都拿不到内容 → 降级且不抛
        stub.contents.clear();
        stub.contents.add(null);
        stub.contents.add(null);

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result.rewritten()).isFalse();
        assertThat(result.standaloneQuestion()).isEqualTo(QUESTION);
        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    void 坏JSON_重试一次带提示_再败降级原问题() {
        stub.contents.clear();
        stub.contents.add("这是自然语言，不是 JSON {{");
        stub.contents.add("还不是 JSON");

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result).isEqualTo(new RewrittenQuery(QUESTION, List.of(), false));
        assertThat(stub.calls).isEqualTo(2);
        assertThat(stub.prompts.get(1)).endsWith("上一次输出不是合法 JSON，只输出 JSON。");
    }

    @Test
    void 重试后合法_成功改写() {
        stub.contents.clear();
        stub.contents.add("坏的");
        stub.contents.add("{\"standalone_question\":\"问题C\",\"keywords\":[\"k\"]}");

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result.rewritten()).isTrue();
        assertThat(result.standaloneQuestion()).isEqualTo("问题C");
        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    void keywords超限裁剪到8个且去重() {
        String json = "{\"standalone_question\":\"q\",\"keywords\":"
                + "[\"k1\",\"k1\",\"k2\",\"k3\",\"k4\",\"k5\",\"k6\",\"k7\",\"k8\",\"k9\",\"k10\"]}";
        stub.contents.clear();
        stub.contents.add(json);

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result.keywords()).hasSize(LlmQueryRewriter.KEYWORDS_MAX);
        assertThat(result.keywords()).containsExactly("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8");
    }

    @Test
    void keywords非字符串项剔除() {
        stub.contents.clear();
        stub.contents.add("{\"standalone_question\":\"q\",\"keywords\":[\"退货\", 1, null, true, \"防水\"]}");

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result.keywords()).containsExactly("退货", "防水");
    }

    @Test
    void standalone空白_降级原问题但关键词保留() {
        stub.contents.clear();
        stub.contents.add("{\"standalone_question\":\"   \",\"keywords\":[\"退货政策\"]}");

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result.rewritten()).isFalse();
        assertThat(result.standaloneQuestion()).isEqualTo(QUESTION);
        assertThat(result.keywords()).containsExactly("退货政策");
    }

    @Test
    void 模型调用异常_降级不抛出() {
        stub.contents.clear();
        stub.failure = new RuntimeException("model api down");

        RewrittenQuery result = rewriter.rewrite(defaultHistory(), null, QUESTION);

        assertThat(result).isEqualTo(new RewrittenQuery(QUESTION, List.of(), false));
    }
}
