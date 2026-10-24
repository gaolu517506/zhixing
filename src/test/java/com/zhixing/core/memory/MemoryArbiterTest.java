package com.zhixing.core.memory;

import com.zhixing.config.MemoryProperties;
import com.zhixing.dao.entity.AgentMemory;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆仲裁器单测（任务书 §3）：prompt 渲染快照 / 三动作解析 / 围栏剥离 / 坏 JSON 重试后
 * 静默放弃 / 非法 action·targetId 剔除。全部桩化离线。
 */
class MemoryArbiterTest {

    static class StubChat implements ChatClient {
        /** 依次返回的输出（末值循环），记录每次收到的 prompt。 */
        final List<String> outputs = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            prompts.add(request.getMessages().get(0).content());
            String out = outputs.isEmpty() ? "[]" : outputs.get(Math.min(prompts.size() - 1, outputs.size() - 1));
            return new ChatResponse(out, "stop", null);
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            throw new UnsupportedOperationException("仲裁只走同步 call");
        }
    }

    private StubChat chat;

    private MemoryArbiter arbiter;

    @BeforeEach
    void setUp() {
        chat = new StubChat();
        arbiter = new MemoryArbiter(chat, new MemoryProperties(), new ObjectMapper());
    }

    private static AgentMemory mem(long id, String content) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setContent(content);
        m.setStatus("ACTIVE");
        return m;
    }

    private static final List<Turn> TURNS = List.of(
            new Turn("user", "我更喜欢深色模式"),
            new Turn("assistant", "好的，已记住你的偏好。"));

    @Test
    void prompt渲染快照_active与turns注入且采样参数正确() {
        chat.outputs.add("[]");

        arbiter.arbitrate(List.of(mem(12, "用户偏好深色模式")), TURNS);

        assertThat(chat.prompts).hasSize(1);
        String prompt = chat.prompts.get(0);
        assertThat(prompt).contains("id=12：用户偏好深色模式");
        assertThat(prompt).contains("用户：我更喜欢深色模式");
        assertThat(prompt).contains("助手：好的，已记住你的偏好。");
        assertThat(prompt).doesNotContain("{active_memories}").doesNotContain("{turns}");
        assertThat(prompt).contains("JSON");
    }

    @Test
    void 合法JSON_三动作全部解析() {
        chat.outputs.add("[{\"action\":\"ADD\",\"content\":\"用户用小米手机\"},"
                + "{\"action\":\"SUPERSEDE\",\"target_id\":12,\"content\":\"用户偏好浅色模式\"},"
                + "{\"action\":\"RETRACT\",\"target_id\":13}]");

        List<MemoryOp> ops = arbiter.arbitrate(
                List.of(mem(12, "旧偏好"), mem(13, "失效记忆")), TURNS);

        assertThat(ops).containsExactly(
                new MemoryOp("ADD", null, "用户用小米手机"),
                new MemoryOp("SUPERSEDE", 12L, "用户偏好浅色模式"),
                new MemoryOp("RETRACT", 13L, null));
    }

    @Test
    void 围栏剥离后解析() {
        chat.outputs.add("```json\n[{\"action\":\"ADD\",\"content\":\"用户在杭州工作\"}]\n```");

        List<MemoryOp> ops = arbiter.arbitrate(List.of(), TURNS);

        assertThat(ops).containsExactly(new MemoryOp("ADD", null, "用户在杭州工作"));
    }

    @Test
    void 坏JSON_重试一次后静默放弃返回空操作() {
        chat.outputs.add("这不是 JSON");
        chat.outputs.add("还不是 JSON");

        List<MemoryOp> ops = arbiter.arbitrate(List.of(), TURNS);

        assertThat(ops).isEmpty();
        assertThat(chat.prompts).hasSize(2);   // 恰好重试一次
        assertThat(chat.prompts.get(1)).contains("不是合法 JSON");
    }

    @Test
    void 非法action与越界targetId剔除_合法项保留() {
        chat.outputs.add("[{\"action\":\"DELETE\",\"content\":\"x\"},"
                + "{\"action\":\"SUPERSEDE\",\"target_id\":999,\"content\":\"越界\"},"
                + "{\"action\":\"ADD\",\"content\":\"   \"},"
                + "{\"action\":\"RETRACT\",\"target_id\":12}]");

        List<MemoryOp> ops = arbiter.arbitrate(List.of(mem(12, "有效记忆")), TURNS);

        assertThat(ops).containsExactly(new MemoryOp("RETRACT", 12L, null));
    }

    @Test
    void 空turns_零LLM调用直接返回空() {
        List<MemoryOp> ops = arbiter.arbitrate(List.of(mem(12, "x")), List.of());

        assertThat(ops).isEmpty();
        assertThat(chat.prompts).isEmpty();
    }
}
