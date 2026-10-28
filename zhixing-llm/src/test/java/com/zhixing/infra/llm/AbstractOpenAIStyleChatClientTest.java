package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基类协议骨架单测（v1.1 S1/S2，直喂字节流离线跑，不建 HTTP 连接）：
 * S1 收尾四形态——[DONE] 到 / finish_reason 到 / 两者都到 / 都不到；
 * S2 error body 三形态——鉴权(401/403)→UNAUTHORIZED、限流(429)→RATE_LIMITED、其余→SERVER_ERROR
 * （流中 error 事件与同步 200+error body 两路同断言）。
 */
class AbstractOpenAIStyleChatClientTest {

    /** 最小子类：协议骨架全在基类，测试只需要 provider 名。 */
    static class TestClient extends AbstractOpenAIStyleChatClient {
        TestClient() {
            super(new OkHttpClient(), "http://127.0.0.1:1", "test-key", "test-model");
        }

        @Override
        public String provider() {
            return "test";
        }
    }

    /** 回调记录器：终局（onComplete 或 onError）二选一记账，断言事件序与收局类型。 */
    static class RecordingCallback implements StreamCallback {
        final List<String> events = new ArrayList<>();
        String finishReason;
        ChatUsage usage;
        List<com.zhixing.infra.llm.dto.ToolCall> toolCalls;
        ModelClientException error;

        @Override
        public void onContent(String delta) {
            events.add("content:" + delta);
        }

        @Override
        public void onThinking(String delta) {
            events.add("thinking:" + delta);
        }

        @Override
        public void onToolCalls(List<com.zhixing.infra.llm.dto.ToolCall> calls) {
            events.add("tool_calls");
            toolCalls = calls;
        }

        @Override
        public void onComplete(String finishReason, ChatUsage usage) {
            events.add("complete");
            this.finishReason = finishReason;
            this.usage = usage;
        }

        @Override
        public void onError(Throwable t) {
            events.add("error");
            this.error = t instanceof ModelClientException m ? m : new ModelClientException(ErrorType.SERVER_ERROR, t.getMessage());
        }
    }

    private static ByteArrayInputStream sse(String... lines) {
        return new ByteArrayInputStream(String.join("\n\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user("hi")))
                .temperature(0.0)
                .build();
    }

    // ===== S1：收尾四形态 =====

    @Test
    void S1形态1_DONE已到而无finish_reason_正常收尾不误判失败() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                "data: [DONE]"), callback);

        // 完整答案已送达：onComplete（finishReason 为 null 是该形态的协议事实），绝不 onError
        assertThat(callback.events).containsExactly("content:你好", "complete");
        assertThat(callback.finishReason).isNull();
        assertThat(callback.error).isNull();
    }

    @Test
    void S1形态2_finish_reason已到而流EOF_正常收尾() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"},\"finish_reason\":\"stop\"}]}"), callback);

        assertThat(callback.events).containsExactly("content:答案", "complete");
        assertThat(callback.finishReason).isEqualTo("stop");
        assertThat(callback.error).isNull();
    }

    @Test
    void S1形态3_finish_reason与DONE都到_正常收尾且usage后到不丢() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"},\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}",
                "data: [DONE]"), callback);

        assertThat(callback.events).containsExactly("content:答案", "complete");
        assertThat(callback.finishReason).isEqualTo("stop");
        assertThat(callback.usage.promptTokens()).isEqualTo(10);
        assertThat(callback.error).isNull();
    }

    @Test
    void S1形态4_finish_reason与DONE都不到_EOF判截断失败() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"半截答案\"}}]}"), callback);

        assertThat(callback.events).containsExactly("content:半截答案", "error");
        assertThat(callback.error.getType()).isEqualTo(ErrorType.INVALID_RESPONSE);
    }

    @Test
    void S1边界_DONE后EOF与读超时不覆盖已收结果() {
        // done 记账后收局已定：finish_reason 与 done 的「或」语义在超时/IO 分支同样成立
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"a\"},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]"), callback);

        assertThat(callback.events).last().isEqualTo("complete");
        assertThat(callback.error).isNull();
    }

    // ===== S2：流中 error 事件三形态（细分映射） =====

    @Test
    void S2流中error_鉴权形态分类为UNAUTHORIZED且不可降级() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"error\":{\"message\":\"Invalid API key\",\"type\":\"authentication_error\","
                        + "\"code\":\"invalid_api_key\"}}"), callback);

        assertThat(callback.events).containsExactly("error");
        assertThat(callback.error.getType()).isEqualTo(ErrorType.UNAUTHORIZED);
        assertThat(callback.error.getType().isFallbackCapable()).isFalse();
    }

    @Test
    void S2流中error_限流形态分类为RATE_LIMITED且不可降级() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"error\":{\"message\":\"Too many requests\",\"type\":\"rate_limit_error\","
                        + "\"code\":\"rate_limit_exceeded\"}}"), callback);

        assertThat(callback.events).containsExactly("error");
        assertThat(callback.error.getType()).isEqualTo(ErrorType.RATE_LIMITED);
        assertThat(callback.error.getType().isFallbackCapable()).isFalse();
    }

    @Test
    void S2流中error_其余形态保持SERVER_ERROR() {
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse(
                "data: {\"error\":{\"message\":\"服务器繁忙\",\"type\":\"server_error\",\"code\":\"internal\"}}"),
                callback);

        assertThat(callback.events).containsExactly("error");
        assertThat(callback.error.getType()).isEqualTo(ErrorType.SERVER_ERROR);
    }

    @Test
    void S2流首即裸JSON错误体_同样按对象内容细分() {
        // 请求流式却收到 200+JSON 错误体（非 SSE 格式）：解析器入口之一，分类同流中 error
        RecordingCallback callback = new RecordingCallback();
        new TestClient().readStream(sse("{\"error\":{\"message\":\"无权访问\",\"status\":403}}"), callback);

        assertThat(callback.events).containsExactly("error");
        assertThat(callback.error.getType()).isEqualTo(ErrorType.UNAUTHORIZED);
    }

    @Test
    void S2数字code形态_401按数值识别为UNAUTHORIZED() {
        TestClient client = new TestClient();

        assertThatThrownBy(() -> client.parseChatBody(
                "{\"error\":{\"message\":\"bad key\",\"code\":401}}"))
                .isInstanceOfSatisfying(ModelClientException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorType.UNAUTHORIZED));
    }

    // ===== S2：同步 200+error body 三形态 =====

    @Test
    void S2同步200包error_鉴权余额形态不再伪装可降级() {
        TestClient client = new TestClient();
        assertThatThrownBy(() -> client.parseChatBody(
                "{\"error\":{\"message\":\"余额不足\",\"code\":\"1113\",\"type\":\"invalid_request_error\"}}"))
                .isInstanceOf(ModelClientException.class)
                .satisfies(e -> assertThat(((ModelClientException) e).getType())
                        .isEqualTo(ErrorType.SERVER_ERROR));
    }

    @Test
    void S2同步200包error_三形态分类断言() {
        TestClient client = new TestClient();

        assertThatThrownBy(() -> client.parseChatBody(
                "{\"error\":{\"message\":\"Authentication Fails\",\"type\":\"authentication_error\"}}"))
                .isInstanceOfSatisfying(ModelClientException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorType.UNAUTHORIZED));

        assertThatThrownBy(() -> client.parseChatBody(
                "{\"error\":{\"message\":\"rate limited\",\"status\":429}}"))
                .isInstanceOfSatisfying(ModelClientException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorType.RATE_LIMITED));

        assertThatThrownBy(() -> client.parseChatBody(
                "{\"error\":{\"message\":\"upstream error\"}}"))
                .isInstanceOfSatisfying(ModelClientException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorType.SERVER_ERROR));
    }

    // ===== 正常路径不回归 =====

    @Test
    void 正常200body解析不受影响() {
        ChatResponse response = new TestClient().parseChatBody(
                "{\"choices\":[{\"message\":{\"content\":\"答案\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");

        assertThat(response.content()).isEqualTo("答案");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(3);
    }
}
