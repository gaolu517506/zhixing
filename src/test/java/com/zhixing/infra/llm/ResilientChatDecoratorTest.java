package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.llm.dto.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 容错装饰器行为测试（任务书 §2，全部假候选链离线）：
 * 正常直通 / 首包超时降级 / 可降级失败切候选 / 非可降级不切换 / 熔断三态流转 / 切换后旧流回调隔离。
 */
class ResilientChatDecoratorTest {

    private static final ChatRequest REQ = ChatRequest.builder().messages(java.util.List.of()).build();

    /** 可编程假候选：call/stream 行为按测试脚本执行。 */
    static class FakeCandidate implements ChatClient {
        private final String name;
        ChatResponse callResponse = new ChatResponse("ok", "stop", new ChatUsage(1, 1));
        ModelClientException callError;
        /** stream 脚本：默认阻塞 5s（模拟首包永不达，装饰器超时/interrupt 后返回）。 */
        Consumer<StreamCallback> streamScript = cb -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        int callCount;
        int streamCount;

        FakeCandidate(String name) {
            this.name = name;
        }

        @Override
        public String provider() {
            return name;
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            callCount++;
            if (callError != null) {
                throw callError;
            }
            return callResponse;
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            streamCount++;
            streamScript.accept(callback);
        }
    }

    /** 上层回调记录器（超时任务/worker 线程并发触碰，用并发容器）。 */
    static class Recorder implements StreamCallback {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onContent(String d) {
            events.add("content:" + d);
        }

        @Override
        public void onThinking(String d) {
            events.add("thinking:" + d);
        }

        @Override
        public void onComplete(String f, ChatUsage u) {
            events.add("complete:" + f);
        }

        @Override
        public void onToolCalls(List<ToolCall> c) {
            events.add("tools:" + c.size());
        }

        @Override
        public void onError(Throwable t) {
            events.add("error:" + (t instanceof ModelClientException m ? m.getType().name() : t.getClass().getSimpleName()));
        }

        String joined() {
            return String.join("|", events);
        }
    }

    private static ModelClientException degrade(ErrorType type) {
        return new ModelClientException(type, type.name());
    }

    private static ResilientChatDecorator decorator(long firstTokenMs, int threshold, int openSeconds,
                                                    int halfOpen, ChatClient... candidates) {
        return new ResilientChatDecorator(java.util.List.of(candidates), firstTokenMs, threshold, openSeconds, halfOpen);
    }

    // ===== call() =====

    @Test
    void call_正常直通_不触碰下一候选() {
        FakeCandidate a = new FakeCandidate("a");
        FakeCandidate b = new FakeCandidate("b");
        ResilientChatDecorator decorator = decorator(15000, 3, 30, 1, a, b);

        ChatResponse response = decorator.call(REQ);

        assertThat(response.content()).isEqualTo("ok");
        assertThat(a.callCount).isEqualTo(1);
        assertThat(b.callCount).isZero();
    }

    @Test
    void call_可降级失败切换候选() {
        FakeCandidate a = new FakeCandidate("a");
        a.callError = degrade(ErrorType.SERVER_ERROR);
        FakeCandidate b = new FakeCandidate("b");
        ResilientChatDecorator decorator = decorator(15000, 3, 30, 1, a, b);

        ChatResponse response = decorator.call(REQ);

        assertThat(response.content()).isEqualTo("ok");
        assertThat(a.callCount).isEqualTo(1);
        assertThat(b.callCount).isEqualTo(1);
    }

    @Test
    void call_非可降级不切换不计熔断() {
        FakeCandidate a = new FakeCandidate("a");
        a.callError = degrade(ErrorType.UNAUTHORIZED);
        FakeCandidate b = new FakeCandidate("b");
        ResilientChatDecorator decorator = decorator(15000, 3, 30, 1, a, b);

        assertThatThrownBy(() -> decorator.call(REQ))
                .isInstanceOf(ModelClientException.class)
                .extracting(e -> ((ModelClientException) e).getType())
                .isEqualTo(ErrorType.UNAUTHORIZED);
        assertThat(b.callCount).isZero();
        // 连续多次 UNAUTHORIZED 不触发熔断（不计入失败），候选保持可用
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> decorator.call(REQ)).isInstanceOf(ModelClientException.class);
        }
        assertThat(a.callCount).isEqualTo(6);
    }

    @Test
    void call_连续可降级失败触发熔断_后续快速失败() {
        FakeCandidate a = new FakeCandidate("a");
        a.callError = degrade(ErrorType.SERVER_ERROR);
        ResilientChatDecorator decorator = decorator(15000, 3, 30, 1, a);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> decorator.call(REQ))
                    .isInstanceOf(ModelClientException.class)
                    .hasMessage(ErrorType.SERVER_ERROR.name());
        }
        assertThat(a.callCount).isEqualTo(3); // 阈值 3：三次都真实执行

        // 第 4 次：熔断打开，直接快速失败，不再触碰候选
        assertThatThrownBy(() -> decorator.call(REQ))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("熔断");
        assertThat(a.callCount).isEqualTo(3);
    }

    // ===== stream() =====

    @Test
    void stream_正常直通() {
        FakeCandidate a = new FakeCandidate("a");
        a.streamScript = cb -> {
            cb.onContent("你好");
            cb.onComplete("stop", null);
        };
        FakeCandidate b = new FakeCandidate("b");
        Recorder recorder = new Recorder();
        decorator(15000, 3, 30, 1, a, b).stream(REQ, recorder);

        assertThat(recorder.joined()).isEqualTo("content:你好|complete:stop");
        assertThat(a.streamCount).isEqualTo(1);
        assertThat(b.streamCount).isZero();
    }

    @Test
    void stream_首包超时切候选_且旧流回调被隔离() {
        FakeCandidate a = new FakeCandidate("a-hangs");
        // 挂起 5s 不出首包；300ms 后装饰器应超时切换并隔离本候选
        a.streamScript = cb -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // interrupt 后模拟旧流迟到的内容——必须被隔离吞掉
            cb.onContent("旧流泄漏");
        };
        FakeCandidate b = new FakeCandidate("b");
        b.streamScript = cb -> {
            cb.onContent("新流内容");
            cb.onComplete("stop", null);
        };
        Recorder recorder = new Recorder();
        ResilientChatDecorator decorator = decorator(300, 3, 30, 1, a, b);

        decorator.stream(REQ, recorder);
        assertThat(recorder.joined()).contains("content:新流内容").contains("complete:stop");
        assertThat(recorder.joined()).doesNotContain("泄漏");

        // 等 A 的迟到回调（500ms 后触发）再验一次：隔离必须持续有效
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
        }
        assertThat(recorder.joined()).doesNotContain("旧流泄漏");
        assertThat(a.streamCount).isEqualTo(1);
        assertThat(b.streamCount).isEqualTo(1);
    }

    @Test
    void stream_首包前可降级错误切候选() {
        FakeCandidate a = new FakeCandidate("a");
        a.streamScript = cb -> cb.onError(degrade(ErrorType.NETWORK_ERROR));
        FakeCandidate b = new FakeCandidate("b");
        b.streamScript = cb -> {
            cb.onContent("来自 b");
            cb.onComplete("stop", null);
        };
        Recorder recorder = new Recorder();
        decorator(5000, 3, 30, 1, a, b).stream(REQ, recorder);

        assertThat(recorder.joined()).contains("content:来自 b").contains("complete:stop");
        assertThat(recorder.joined()).doesNotContain("error:");
        assertThat(a.streamCount).isEqualTo(1);
        assertThat(b.streamCount).isEqualTo(1);
    }

    @Test
    void stream_非可降级错误不切换直接终态() {
        FakeCandidate a = new FakeCandidate("a");
        a.streamScript = cb -> cb.onError(degrade(ErrorType.UNAUTHORIZED));
        FakeCandidate b = new FakeCandidate("b");
        Recorder recorder = new Recorder();
        decorator(5000, 3, 30, 1, a, b).stream(REQ, recorder);

        assertThat(recorder.joined()).isEqualTo("error:UNAUTHORIZED");
        assertThat(b.streamCount).isZero();
    }

    @Test
    void stream_出首包后失败不切换_防内容重复() {
        FakeCandidate a = new FakeCandidate("a");
        a.streamScript = cb -> {
            cb.onContent("部分内容");
            cb.onError(degrade(ErrorType.NETWORK_ERROR));
        };
        FakeCandidate b = new FakeCandidate("b");
        Recorder recorder = new Recorder();
        decorator(5000, 3, 30, 1, a, b).stream(REQ, recorder);

        assertThat(recorder.joined()).contains("content:部分内容").contains("error:NETWORK_ERROR");
        assertThat(b.streamCount).isZero(); // 上层已收到部分内容，切换会造成重复
    }

    @Test
    void stream_全部候选熔断_以错误终态收尾() {
        FakeCandidate a = new FakeCandidate("a");
        ResilientChatDecorator decorator = decorator(5000, 1, 30, 1, a);
        Recorder recorder = new Recorder();
        // 阈值 1：首次失败即熔断 → 无候选可用 → 装饰器自己交付 error
        decorator.stream(REQ, recorder);
        // 第二次请求：同一装饰器的候选仍熔断 → 直接交付错误，绝不触碰候选
        decorator.stream(REQ, recorder);

        // 每条流恰好一个 error 终态（终态成对规则的装饰器侧），候选只被真实执行一次
        assertThat(recorder.joined()).isEqualTo("error:SERVER_ERROR|error:SERVER_ERROR");
        assertThat(a.streamCount).isEqualTo(1);
    }

    // ===== 熔断三态流转（call 路径，确定性）=====

    @Test
    void 熔断三态_开_半开试探成功_闭合() {
        FakeCandidate a = new FakeCandidate("a");
        ResilientChatDecorator decorator = decorator(15000, 2, 1, 1, a); // 阈值2 / 开1s / 半开1次

        // 两次可降级失败 → 打开
        a.callError = degrade(ErrorType.SERVER_ERROR);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> decorator.call(REQ)).hasMessage(ErrorType.SERVER_ERROR.name());
        }
        // 打开期（1s 内）：快速失败，不触碰候选
        assertThatThrownBy(() -> decorator.call(REQ)).hasMessageContaining("熔断");
        assertThat(a.callCount).isEqualTo(2);

        // 1s 后 → 半开，放行 1 次试探；试探仍失败 → 重新打开
        sleep(1100);
        assertThatThrownBy(() -> decorator.call(REQ)).hasMessage(ErrorType.SERVER_ERROR.name());
        assertThat(a.callCount).isEqualTo(3);
        assertThatThrownBy(() -> decorator.call(REQ)).hasMessageContaining("熔断"); // 半开名额已耗尽
        assertThat(a.callCount).isEqualTo(3);

        // 再过 1s → 再次半开；试探成功 → 闭合，恢复正常
        sleep(1100);
        a.callError = null;
        assertThat(decorator.call(REQ).content()).isEqualTo("ok");
        assertThat(decorator.call(REQ).content()).isEqualTo("ok"); // 闭合态正常放行
        assertThat(a.callCount).isEqualTo(5);
    }

    @Test
    void 熔断三态_半开试探成功闭合后_失败计数清零() {
        FakeCandidate a = new FakeCandidate("a");
        ResilientChatDecorator decorator = decorator(15000, 2, 0, 1, a); // openSeconds=0：立即半开，免等待

        a.callError = degrade(ErrorType.SERVER_ERROR);
        assertThatThrownBy(() -> decorator.call(REQ)); // 失败1
        assertThatThrownBy(() -> decorator.call(REQ)); // 失败2 → 打开；openSeconds=0 → 立即半开
        assertThatThrownBy(() -> decorator.call(REQ)); // 半开试探失败 → 重新打开（又立即半开）
        assertThatThrownBy(() -> decorator.call(REQ)); // 再试探失败 → 再打开
        assertThat(a.callCount).isEqualTo(4);

        // 试探成功 → 闭合，失败计数清零：之后一次失败不应直接熔断
        a.callError = null;
        assertThat(decorator.call(REQ).content()).isEqualTo("ok");
        a.callError = degrade(ErrorType.SERVER_ERROR);
        assertThatThrownBy(() -> decorator.call(REQ)).hasMessage(ErrorType.SERVER_ERROR.name()); // 失败1，未熔断
        assertThat(a.callCount).isEqualTo(6);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
