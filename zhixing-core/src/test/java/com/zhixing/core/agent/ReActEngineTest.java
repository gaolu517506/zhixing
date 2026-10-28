package com.zhixing.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.AgentProperties;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.AgentRunStepMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.sse.SseEventSink;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import com.zhixing.config.TraceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环状态机单测（任务书 §4，全部桩化离线）：直达答案 / 单工具循环（messages 快照
 * 精确顺序断言）/ 写工具挂起 / approve+reject 续跑 / maxIterations 强制收尾 / 工具失败自纠 /
 * 连续失败注入 / 取消。断言强度红线：消息回填顺序错一个即测试红。
 */
class ReActEngineTest {

    /** 按轮脚本化 fake：第 N 次 stream 走 rounds[N]，记录全部请求供 messages 快照断言。 */
    static class ScriptedChat implements ChatClient {
        record Round(String content, List<ToolCall> calls) {
            static Round answer(String text) {
                return new Round(text, null);
            }

            static Round tools(ToolCall... calls) {
                return new Round(null, List.of(calls));
            }
        }

        final List<Round> rounds = new ArrayList<>();
        final List<ChatRequest> requests = new ArrayList<>();
        int cursor = 0;

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            throw new UnsupportedOperationException("引擎只走 stream");
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            requests.add(request);
            Round round = rounds.get(Math.min(cursor, rounds.size() - 1));
            cursor++;
            try {
                if (round.calls() != null) {
                    callback.onToolCalls(round.calls());
                    callback.onComplete("tool_calls", new ChatUsage(10, 2));
                } else {
                    callback.onContent(round.content());
                    callback.onComplete("stop", new ChatUsage(10, 5));
                }
            } catch (RuntimeException e) {
                callback.onError(e);
            }
        }
    }

    static class FakeTool implements Tool {
        private final String name;
        private final boolean confirm;
        private final Function<Map<String, Object>, ToolResult> fn;

        FakeTool(String name, boolean confirm, Function<Map<String, Object>, ToolResult> fn) {
            this.name = name;
            this.confirm = confirm;
            this.fn = fn;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake " + name;
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            return fn.apply(args);
        }

        @Override
        public boolean requireConfirm() {
            return confirm;
        }
    }

    static class RecordingSink implements SseEventSink {
        final List<String> events = new ArrayList<>();
        boolean completed;

        @Override
        public void send(String event, Object data) {
            try {
                events.add(event + ":" + new ObjectMapper().writeValueAsString(data));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    private ScriptedChat chat;
    private QaMessageMapper messageMapper;
    private AgentRunMapper runMapper;
    private AgentRunStepMapper stepMapper;
    private ReActEngine engine;
    private AgentProperties props;
    private RecordingSink sink;

    private static final ToolCall CALL1 = new ToolCall("call-1", "search_knowledge", "{\"query\":\"K80 电池\"}");

    @BeforeEach
    void setUp() {
        chat = new ScriptedChat();
        messageMapper = mock(QaMessageMapper.class);
        runMapper = mock(AgentRunMapper.class);
        stepMapper = mock(AgentRunStepMapper.class);
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);
        props = new AgentProperties();
        props.setMaxContextChars(1_000_000);
        props.setToolTimeoutMs(2_000);

        TraceRecorder traceRecorder = new TraceRecorder(mock(TraceRunMapper.class),
                mock(TraceNodeMapper.class), new TraceProperties());
        ToolExecutor executor = new ToolExecutor(stepMapper, traceRecorder, props, new ObjectMapper());
        ConfirmManager confirmManager = new ConfirmManager(runMapper, new ObjectMapper());
        ContextManager contextManager = new ContextManager(props, chat);
        sink = new RecordingSink();
        engine = new ReActEngine(chat, new ToolRegistry(List.of()), executor, contextManager,
                confirmManager, traceRecorder, props, messageMapper, runMapper, stepMapper);
    }

    private ReActEngine engineWithTools(List<Tool> tools) {
        return newEngine(chat, tools);
    }

    private ReActEngine newEngine(ChatClient client, List<Tool> tools) {
        TraceRecorder traceRecorder = new TraceRecorder(mock(TraceRunMapper.class),
                mock(TraceNodeMapper.class), new TraceProperties());
        ToolExecutor executor = new ToolExecutor(stepMapper, traceRecorder, props, new ObjectMapper());
        return new ReActEngine(client, new ToolRegistry(tools), executor,
                new ContextManager(props, client), new ConfirmManager(runMapper, new ObjectMapper()),
                traceRecorder, props, messageMapper, runMapper, stepMapper);
    }

    private AgentRunContext ctx() {
        List<com.zhixing.infra.llm.dto.ChatMessage> messages = new ArrayList<>();
        messages.add(com.zhixing.infra.llm.dto.ChatMessage.system("系统规则"));
        messages.add(com.zhixing.infra.llm.dto.ChatMessage.user("知识库里有没有 K80 电池的信息？"));
        // 一个 run 一个 TraceContext（修复④）：测试里与生产同构地显式打开
        return new AgentRunContext(1L, 77L, 9L, messages, 1,
                new TraceContext(1L, new java.util.concurrent.atomic.AtomicInteger()));
    }

    private void run(ReActEngine eng, AgentRunContext c, AgentCancellation cancellation) {
        eng.run(c, cancellation, sink);
    }

    private List<String> eventNames() {
        return sink.events.stream().map(e -> e.substring(0, e.indexOf(':'))).toList();
    }

    // ===== ① 直达答案 =====

    @Test
    void 直达答案_content与done_状态COMPLETED且回答落qa_message() {
        chat.rounds.add(ScriptedChat.Round.answer("K80 电池为 6550mAh [1]。"));

        run(engine, ctx(), new AgentCancellation());

        assertThat(eventNames()).containsExactly("content", "done");
        assertThat(sink.events.get(1)).startsWith("done:").contains("\"messageId\":");
        assertThat(sink.completed).isTrue();

        ArgumentCaptor<QaMessage> msg = ArgumentCaptor.forClass(QaMessage.class);
        verify(messageMapper).insert(msg.capture());
        assertThat(msg.getValue().getEngine()).isEqualTo("agent");
        assertThat(msg.getValue().getContent()).isEqualTo("K80 电池为 6550mAh [1]。");

        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo("COMPLETED");
    }

    // ===== ② 单工具循环：messages 快照精确顺序（红线断言） =====

    @Test
    void 单工具循环_回填顺序assistant后紧跟配对tool结果() {
        chat.rounds.add(ScriptedChat.Round.tools(CALL1));
        chat.rounds.add(ScriptedChat.Round.answer("K80 电池 6550mAh。"));
        ReActEngine eng = engineWithTools(List.of(
                new FakeTool("search_knowledge", false, args -> ToolResult.ok("检索到：K80 电池 6550mAh"))));

        run(eng, ctx(), new AgentCancellation());

        // 第 2 轮请求的 messages：system → user → assistant(toolCalls) → tool(配对)——顺序错一个即 400
        List<com.zhixing.infra.llm.dto.ChatMessage> msgs = chat.requests.get(1).getMessages();
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0).role()).isEqualTo("system");
        assertThat(msgs.get(1).role()).isEqualTo("user");
        assertThat(msgs.get(2).role()).isEqualTo("assistant");
        assertThat(msgs.get(2).content()).isNull();
        assertThat(msgs.get(2).toolCalls()).hasSize(1);
        assertThat(msgs.get(2).toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(msgs.get(3).role()).isEqualTo("tool");
        assertThat(msgs.get(3).toolCallId()).isEqualTo("call-1");
        assertThat(msgs.get(3).content()).isEqualTo("检索到：K80 电池 6550mAh");

        assertThat(eventNames()).containsExactly("tool_call", "tool_result", "content", "done");
    }

    // ===== ③ 写工具挂起 =====

    @Test
    void 写工具挂起_confirm_request加done_快照含assistant调用且无工具结果() throws Exception {
        ToolCall deleteCall = new ToolCall("call-d", "delete_document", "{\"documentId\":123}");
        chat.rounds.add(ScriptedChat.Round.tools(deleteCall));
        ReActEngine eng = engineWithTools(List.of(
                new FakeTool("delete_document", true, args -> ToolResult.ok("不应被执行"))));

        run(eng, ctx(), new AgentCancellation());

        assertThat(eventNames()).containsExactly("tool_call", "confirm_request", "done");
        assertThat(sink.events.get(1)).contains("\"runId\":1").contains("delete_document");
        assertThat(sink.completed).isTrue();
        // 危险工具未被执行：只有本轮 LLM step，没有任何 TOOL step
        ArgumentCaptor<com.zhixing.dao.entity.AgentRunStep> step =
                ArgumentCaptor.forClass(com.zhixing.dao.entity.AgentRunStep.class);
        verify(stepMapper).insert(step.capture());
        assertThat(step.getValue().getType()).isEqualTo("LLM");

        // run 行写两次：① suspend 的快照落库（WAITING_CONFIRM + snapshot + pending）
        // ② 引擎挂起收尾的 patch（tokens/cost）——两者都是规格内行为
        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper, org.mockito.Mockito.times(2)).updateById(run.capture());
        AgentRun suspendPatch = run.getAllValues().get(0);
        AgentRun tailPatch = run.getAllValues().get(1);
        assertThat(suspendPatch.getStatus()).isEqualTo("WAITING_CONFIRM");
        assertThat(tailPatch.getStatus()).isEqualTo("WAITING_CONFIRM");
        JsonNode snapshot = new ObjectMapper().readTree(suspendPatch.getMessagesSnapshot());
        assertThat(snapshot).hasSize(3);   // system + user + assistant(toolCalls)
        assertThat(snapshot.get(2).path("toolCalls").get(0).path("id").asText()).isEqualTo("call-d");
        JsonNode pending = new ObjectMapper().readTree(suspendPatch.getPendingTool());
        assertThat(pending.path("name").asText()).isEqualTo("delete_document");
        assertThat(pending.path("toolCallId").asText()).isEqualTo("call-d");
    }

    // ===== ④ approve / reject 续跑（引擎从「结果已回填」状态继续） =====

    @Test
    void approve续跑_补写结果后引擎继续直至完成() {
        List<com.zhixing.infra.llm.dto.ChatMessage> resumed = new ArrayList<>(List.of(
                com.zhixing.infra.llm.dto.ChatMessage.system("系统规则"),
                com.zhixing.infra.llm.dto.ChatMessage.user("把 K80 那篇删了"),
                com.zhixing.infra.llm.dto.ChatMessage.assistantWithToolCalls(
                        List.of(new ToolCall("call-d", "delete_document", "{\"documentId\":123}"))),
                com.zhixing.infra.llm.dto.ChatMessage.toolResult("call-d", "已删除文档：09-fault-wifi.md（kb=9）")));
        chat.rounds.add(ScriptedChat.Round.answer("已按你的确认删除该文档。"));

        AgentRunContext resumeCtx = new AgentRunContext(1L, 77L, 9L, resumed, 2,
                new TraceContext(1L, new java.util.concurrent.atomic.AtomicInteger()));
        run(engine, resumeCtx, new AgentCancellation());

        assertThat(eventNames()).containsExactly("content", "done");
        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getValue().getIterations()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void reject续跑_拒绝文案作为tool结果回填_引擎向用户解释() {
        List<com.zhixing.infra.llm.dto.ChatMessage> resumed = new ArrayList<>(List.of(
                com.zhixing.infra.llm.dto.ChatMessage.system("系统规则"),
                com.zhixing.infra.llm.dto.ChatMessage.user("把 K80 那篇删了"),
                com.zhixing.infra.llm.dto.ChatMessage.assistantWithToolCalls(
                        List.of(new ToolCall("call-d", "delete_document", "{\"documentId\":123}"))),
                com.zhixing.infra.llm.dto.ChatMessage.toolResult("call-d", "用户拒绝了该操作，请调整方案或向用户说明。")));
        chat.rounds.add(ScriptedChat.Round.answer("好的，未执行删除。建议改为归档或重命名。"));

        AgentRunContext resumeCtx = new AgentRunContext(1L, 77L, 9L, resumed, 2,
                new TraceContext(1L, new java.util.concurrent.atomic.AtomicInteger()));
        run(engine, resumeCtx, new AgentCancellation());

        // 续跑第 1 轮请求里带拒绝文案的 tool 消息
        List<com.zhixing.infra.llm.dto.ChatMessage> msgs = chat.requests.get(0).getMessages();
        assertThat(msgs.get(3).content()).contains("用户拒绝");
        assertThat(eventNames()).containsExactly("content", "done");
    }

    // ===== ⑤ maxIterations 强制收尾（DL-15 ②） =====

    @Test
    void maxIterations耗尽_注入提示且最后一轮不带工具() {
        props.setMaxIterations(2);
        TraceRecorder traceRecorder = new TraceRecorder(mock(TraceRunMapper.class),
                mock(TraceNodeMapper.class), new TraceProperties());
        ToolExecutor executor = new ToolExecutor(stepMapper, traceRecorder, props, new ObjectMapper());
        ReActEngine eng = new ReActEngine(chat, new ToolRegistry(List.of(
                new FakeTool("search_knowledge", false, args -> ToolResult.ok("结果")))),
                executor, new ContextManager(props, chat), new ConfirmManager(runMapper, new ObjectMapper()),
                traceRecorder, props, messageMapper, runMapper, stepMapper);
        chat.rounds.add(ScriptedChat.Round.tools(CALL1));
        chat.rounds.add(ScriptedChat.Round.tools(CALL1));
        chat.rounds.add(ScriptedChat.Round.answer("基于已有信息作答。"));

        run(eng, ctx(), new AgentCancellation());

        // 第 3 轮请求：注入提示在列，且 tools 为空（强制无工具）
        ChatRequest finalRequest = chat.requests.get(2);
        assertThat(finalRequest.getMessages())
                .anyMatch(m -> "user".equals(m.role()) && m.content().contains("已达工具调用上限"));
        assertThat(finalRequest.getTools()).isEmpty();
        assertThat(eventNames()).containsExactly("tool_call", "tool_result", "tool_call",
                "tool_result", "content", "done");
        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo("COMPLETED");
    }

    // ===== ⑥ 工具失败自纠（不终态） =====

    @Test
    void 工具异常转失败回填_模型自纠_链路COMPLETED() {
        chat.rounds.add(ScriptedChat.Round.tools(CALL1));
        chat.rounds.add(ScriptedChat.Round.answer("检索工具故障，根据已有知识回答：6550mAh。"));
        ReActEngine eng = engineWithTools(List.of(new FakeTool("search_knowledge", false,
                args -> {
                    throw new IllegalStateException("pg down");
                })));

        run(eng, ctx(), new AgentCancellation());

        assertThat(sink.events.get(1)).startsWith("tool_result:").contains("\"success\":false");
        // 回填的是面向模型的自纠文案，不是异常堆栈
        ChatRequest second = chat.requests.get(1);
        assertThat(second.getMessages().get(3).content()).contains("请调整参数重试或改用其他方式");
        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo("COMPLETED");
    }

    // ===== ⑦ 连续同名失败 ≥3 注入提示防死循环 =====

    @Test
    void 连续三次同名失败_注入user提示防死循环() {
        ToolCall same = new ToolCall("call-x", "search_knowledge", "{\"query\":\"x\"}");
        chat.rounds.add(ScriptedChat.Round.tools(same));
        chat.rounds.add(ScriptedChat.Round.tools(same));
        chat.rounds.add(ScriptedChat.Round.tools(same));
        chat.rounds.add(ScriptedChat.Round.answer("改用已有信息作答。"));
        ReActEngine eng = engineWithTools(List.of(new FakeTool("search_knowledge", false,
                args -> ToolResult.fail("检索失败", "pg down"))));

        run(eng, ctx(), new AgentCancellation());

        ChatRequest fourth = chat.requests.get(3);
        assertThat(fourth.getMessages())
                .anyMatch(m -> "user".equals(m.role()) && m.content().contains("已连续失败 3 次"));
        assertThat(eventNames().stream().filter("tool_result"::equals).count()).isEqualTo(3);
        assertThat(eventNames()).last().matches(n -> n.equals("done"));
    }

    // ===== ⑧ 取消 =====

    @Test
    void 已取消的运行_checkpoints触发_cancel加done_状态CANCELLED() {
        chat.rounds.add(ScriptedChat.Round.answer("不会被用到"));
        AgentCancellation cancellation = new AgentCancellation();
        cancellation.cancel();

        run(engine, ctx(), cancellation);

        assertThat(eventNames()).containsExactly("cancel", "done");
        assertThat(sink.completed).isTrue();
        verify(messageMapper, never()).insert(any(QaMessage.class));
        assertThat(sink.events).noneMatch(e -> e.startsWith("content:"));
        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo("CANCELLED");
    }

    // ===== ⑨ trace 节点 seq 严格递增（v1.1 修复④，任务书验收断言） =====

    @Test
    void 多轮run的trace节点seq严格递增() {
        TraceNodeMapper seqNodeMapper = mock(TraceNodeMapper.class);
        TraceRecorder traceRecorder = new TraceRecorder(mock(TraceRunMapper.class),
                seqNodeMapper, new TraceProperties());
        ToolExecutor executor = new ToolExecutor(stepMapper, traceRecorder, props, new ObjectMapper());
        ReActEngine eng = new ReActEngine(chat, new ToolRegistry(List.of(
                new FakeTool("search_knowledge", false, args -> ToolResult.ok("检索结果")))),
                executor, new ContextManager(props, chat), new ConfirmManager(runMapper, new ObjectMapper()),
                traceRecorder, props, messageMapper, runMapper, stepMapper);
        chat.rounds.add(ScriptedChat.Round.tools(CALL1));
        chat.rounds.add(ScriptedChat.Round.answer("完成。"));

        run(eng, ctx(), new AgentCancellation());

        // llm → tool → llm 三节点：seq 1,2,3 严格递增——引擎与 ToolExecutor 收到的
        // ctx 同源（AgentRunContext.traceCtx 贯穿），循环内不再新建计数器
        ArgumentCaptor<com.zhixing.dao.entity.TraceNode> nodes =
                ArgumentCaptor.forClass(com.zhixing.dao.entity.TraceNode.class);
        verify(seqNodeMapper, times(3)).insert(nodes.capture());
        assertThat(nodes.getAllValues())
                .extracting(com.zhixing.dao.entity.TraceNode::getSeq)
                .containsExactly(1, 2, 3);
        assertThat(nodes.getAllValues())
                .extracting(com.zhixing.dao.entity.TraceNode::getName)
                .containsExactly("llm", "tool", "llm");
    }

    @Test
    void 流中取消_onError转译_cancel加done_已生成内容落库() {
        AgentCancellation cancellation = new AgentCancellation();
        ChatClient cancellingChat = new ChatClient() {
            @Override
            public String provider() {
                return "fake";
            }

            @Override
            public ChatResponse call(ChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stream(ChatRequest request, StreamCallback callback) {
                callback.onContent("已生成的部分内容");
                // 模拟并发取消后 Future.cancel 打断读 → onError 回调
                cancellation.cancel();
                callback.onError(new IllegalStateException("stream interrupted"));
            }
        };

        run(newEngine(cancellingChat, List.of()), ctx(), cancellation);

        assertThat(eventNames()).containsExactly("content", "cancel", "done");
        // 取消语义：已生成内容先落库再发终态
        ArgumentCaptor<QaMessage> msg = ArgumentCaptor.forClass(QaMessage.class);
        verify(messageMapper).insert(msg.capture());
        assertThat(msg.getValue().getContent()).isEqualTo("已生成的部分内容");
        assertThat(msg.getValue().getEngine()).isEqualTo("agent");
        ArgumentCaptor<AgentRun> run = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo("CANCELLED");
    }
}
