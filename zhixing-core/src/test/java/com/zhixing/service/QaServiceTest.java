package com.zhixing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.MemoryProperties;
import com.zhixing.config.QaProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.config.RewriteProperties;
import com.zhixing.config.SummaryProperties;
import com.zhixing.config.TraceProperties;
import com.zhixing.core.memory.MemoryView;
import com.zhixing.core.memory.NoopQueryRewriter;
import com.zhixing.core.memory.QueryRewriter;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.memory.SessionMemory;
import com.zhixing.core.memory.Summarizer;
import com.zhixing.core.memory.Turn;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.ErrorType;
import com.zhixing.infra.llm.ModelClientException;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.sse.SseEventSink;
import com.zhixing.infra.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA 链路单测（任务书 T2-A §3）：fake ChatClient + mock RetrievalEngine/Mapper/SessionMemory，
 * 断言 SSE 事件序、多轮 messages 组装、摘要触发落库、重写降级与开关等价性。全程不联网。
 * 阶段 1 六用例全量保留（适配五参检索桩），断言强度只增不减。
 */
class QaServiceTest {

    private QaSessionMapper sessionMapper;

    private QaMessageMapper messageMapper;

    private TraceNodeMapper traceNodeMapper;

    private RetrievalEngine retrievalEngine;

    private TraceRunMapper traceRunMapper;

    private FakeChatClient fakeChat;

    private QaService qaService;

    private RecordingSink sink;

    /** 事件记录器：按序记 "event:dataJson"，终态成对断言的数据源。 */
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

    /** 固定行为 fake：默认两段 delta + stop 终态（带 usage）；记录收到的 ChatRequest。 */
    static class FakeChatClient implements ChatClient {
        List<String> deltas = List.of("小米 15 Ultra 搭载", "6000mAh 电池 [1]。");
        RuntimeException failureAfterDeltas;
        final ChatUsage usage = new ChatUsage(18, 10);
        ChatRequest lastStreamRequest;

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            return new ChatResponse(String.join("", deltas), "stop", usage);
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            lastStreamRequest = request;
            try {
                for (String delta : deltas) {
                    callback.onContent(delta);
                }
                if (failureAfterDeltas != null) {
                    throw failureAfterDeltas;
                }
                callback.onComplete("stop", usage);
            } catch (RuntimeException e) {
                callback.onError(e);
            }
        }
    }

    @AfterEach
    void clearAuth() {
        com.zhixing.common.auth.AuthContext.clear();
    }

    @BeforeEach
    void setUp() {
        com.zhixing.common.auth.AuthContext.set(
                new com.zhixing.common.auth.CurrentUser(1L, "tester", "USER", com.zhixing.common.auth.AuthChannel.WEB));
        sessionMapper = mock(QaSessionMapper.class);
        messageMapper = mock(QaMessageMapper.class);
        traceRunMapper = mock(TraceRunMapper.class);
        traceNodeMapper = mock(TraceNodeMapper.class);
        retrievalEngine = mock(RetrievalEngine.class);
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);
        when(sessionMapper.insert(any(QaSession.class))).thenReturn(1);
        // 真实 SessionMemory 走 mocked mapper：空会话（summary=null、窗口空、0 轮）→ 阶段 1 形态
        when(messageMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        fakeChat = new FakeChatClient();

        CitedChunk hit = new CitedChunk(9001L, 101L, "01-param-xiaomi-15-ultra.md", "小米15 Ultra 电池 6000mAh", 0.032);
        when(retrievalEngine.retrieve(anyLong(), any(), any(), any(RetrieveBudget.class), any()))
                .thenReturn(new RetrievalResult(List.of(hit), List.of(hit), List.of(hit)));
        // 开关关闭分支走阶段 1 的 4 参重载
        when(retrievalEngine.retrieve(anyLong(), any(), any(RetrieveBudget.class), any()))
                .thenReturn(new RetrievalResult(List.of(hit), List.of(hit), List.of(hit)));

        sink = new RecordingSink();
        qaService = newQaService(new TraceProperties());
    }

    private QaService newQaService(TraceProperties traceProps) {
        return newQaService(traceProps,
                new SessionMemory(sessionMapper, messageMapper, new MemoryProperties()),
                new Summarizer(fakeChat, new SummaryProperties()),
                new NoopQueryRewriter(),
                new RewriteProperties());
    }

    /** 长期记忆门面桩：默认 null 注入块 + no-op 触发（生产实现自身保证不抛）。 */
    private com.zhixing.core.memory.LongTermMemoryService memoryStub() {
        return mock(com.zhixing.core.memory.LongTermMemoryService.class);
    }

    private QaService newQaService(TraceProperties traceProps, SessionMemory memory, Summarizer summarizer,
                                   QueryRewriter rewriter, RewriteProperties rewriteProps) {
        return newQaService(traceProps, memory, summarizer, rewriter, rewriteProps, memoryStub());
    }

    private QaService newQaService(TraceProperties traceProps, SessionMemory memory, Summarizer summarizer,
                                   QueryRewriter rewriter, RewriteProperties rewriteProps,
                                   com.zhixing.core.memory.LongTermMemoryService longTermMemory) {
        return new QaService(fakeChat, retrievalEngine, sessionMapper, messageMapper,
                new TraceRecorder(traceRunMapper, traceNodeMapper, traceProps),
                new RetrieveProperties(), new QaProperties(), new ObjectMapper(), Runnable::run,
                memory, summarizer, rewriter, new SummaryProperties(), rewriteProps, longTermMemory,
                mock(PermissionService.class),
                stubRouter(), new com.zhixing.config.RouteProperties());
    }

    /** F11 路由桩：既有用例均显式带 kbId，路由器不被触达；mock 返回回落以防万一。 */
    @SuppressWarnings("unchecked")
    private IntentRouter stubRouter() {
        IntentRouter router = mock(IntentRouter.class);
        org.mockito.Mockito.when(router.route(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> new IntentRouter.RouteDecision("FALLBACK", null, null, null,
                        new java.util.ArrayList<>(inv.getArgument(1))));
        return router;
    }

    private static QaStreamRequest request() {
        QaStreamRequest req = new QaStreamRequest();
        req.setKbId(9L);
        req.setQuestion("小米15 Ultra 的电池容量是多少？官方数据给出实测续航表现");
        return req;
    }

    // ===== 阶段 1 六用例回归（语义不变） =====

    @Test
    void 正常流_事件序start_content_sources_done且消息落库() {
        qaService.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).hasSize(5);
        assertThat(sink.events.get(0)).startsWith("start:").contains("\"engine\":\"workflow\"");
        assertThat(sink.events.get(1)).startsWith("content:").contains("小米 15 Ultra 搭载");
        assertThat(sink.events.get(2)).startsWith("content:").contains("6000mAh");
        assertThat(sink.events.get(3)).startsWith("sources:").contains("9001").contains("01-param-xiaomi-15-ultra.md");
        assertThat(sink.events.get(4)).startsWith("done:").contains("\"promptTokens\":18").contains("\"completionTokens\":10");
        assertThat(sink.completed).isTrue();

        // assistant 消息落库字段
        ArgumentCaptor<QaMessage> captor = ArgumentCaptor.forClass(QaMessage.class);
        verify(messageMapper).insert(captor.capture());
        QaMessage assistant = captor.getValue();
        assertThat(assistant.getRole()).isEqualTo("assistant");
        assertThat(assistant.getContent()).isEqualTo("小米 15 Ultra 搭载6000mAh 电池 [1]。");
        assertThat(assistant.getSources()).contains("9001").contains("6000mAh");
        assertThat(assistant.getPromptTokens()).isEqualTo(18);
        assertThat(assistant.getCompletionTokens()).isEqualTo(10);
        assertThat(assistant.getCostMs()).isNotNull();

        // trace 节点：空会话下无 rewrite/summarize，只有 QaService 自己埋的 llm_chat
        ArgumentCaptor<TraceNode> nodeCaptor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getName()).isEqualTo("llm_chat");
        assertThat(nodeCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void 检索异常_事件序start_error_done且不落assistant消息() {
        when(retrievalEngine.retrieve(anyLong(), any(), any(), any(RetrieveBudget.class), any()))
                .thenThrow(new IllegalStateException("db down"));

        qaService.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).hasSize(3);
        assertThat(sink.events.get(0)).startsWith("start:");
        assertThat(sink.events.get(1)).startsWith("error:").contains("\"code\":\"B0001\"");
        assertThat(sink.events.get(2)).startsWith("done:").contains("\"messageId\":null");
        assertThat(sink.completed).isTrue();
        // 无已生成内容：不落 assistant 消息（"先落库"只在有内容时适用）
        verify(messageMapper, never()).insert(any(QaMessage.class));
    }

    @Test
    void 流中模型失败_已生成内容先落库再error加done() {
        fakeChat.deltas = List.of("小米 15 Ultra 搭载");
        fakeChat.failureAfterDeltas = new ModelClientException(ErrorType.TIMEOUT, "stream timeout");

        qaService.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).hasSize(4);
        assertThat(sink.events.get(0)).startsWith("start:");
        assertThat(sink.events.get(1)).startsWith("content:");
        assertThat(sink.events.get(2)).startsWith("error:").contains("\"code\":\"C0001\"");
        assertThat(sink.events.get(3)).startsWith("done:");
        assertThat(sink.completed).isTrue();
        // 已生成内容先落库：partial content + sources 都要留下
        ArgumentCaptor<QaMessage> captor = ArgumentCaptor.forClass(QaMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("小米 15 Ultra 搭载");
        assertThat(captor.getValue().getSources()).isNotNull();
    }

    @Test
    void streamQa入口_无sessionId时建会话并保存user消息() {
        qaService.streamQa(request());

        ArgumentCaptor<QaSession> sessionCaptor = ArgumentCaptor.forClass(QaSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getTitle()).hasSize(20);
        // user + assistant 两条消息
        ArgumentCaptor<QaMessage> msgCaptor = ArgumentCaptor.forClass(QaMessage.class);
        verify(messageMapper, times(2)).insert(msgCaptor.capture());
        assertThat(msgCaptor.getAllValues()).extracting(QaMessage::getRole)
                .containsExactly("user", "assistant");
    }

    @Test
    void 收尾落库失败_回答仍完整送达_done以null消息id标记未持久化() {
        // 实测事故回归：sources 列 jsonb 写入失败曾让连接在 done 之前被 complete() 关闭；
        // 修复后落库失败不升级为链路失败——回答已送达，done.messageId=null 标记未持久化
        when(messageMapper.insert(any(QaMessage.class))).thenThrow(new IllegalStateException("jsonb write fail"));

        qaService.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).hasSize(5);
        assertThat(sink.events.get(0)).startsWith("start:");
        assertThat(sink.events.get(3)).startsWith("sources:");
        assertThat(sink.events.get(4)).startsWith("done:").contains("\"messageId\":null");
        assertThat(sink.completed).isTrue();
    }

    @Test
    void trace关闭时_链路正常且零trace写入() {
        TraceProperties off = new TraceProperties();
        off.setEnabled(false);
        TraceNodeMapper quietNodeMapper = mock(TraceNodeMapper.class);
        TraceRunMapper quietRunMapper = mock(TraceRunMapper.class);
        QaService service = new QaService(fakeChat, retrievalEngine, sessionMapper, messageMapper,
                new TraceRecorder(quietRunMapper, quietNodeMapper, off),
                new RetrieveProperties(), new QaProperties(), new ObjectMapper(), Runnable::run,
                new SessionMemory(sessionMapper, messageMapper, new MemoryProperties()),
                new Summarizer(fakeChat, new SummaryProperties()),
                new NoopQueryRewriter(), new SummaryProperties(), new RewriteProperties(), memoryStub(),
                mock(PermissionService.class),
                stubRouter(), new com.zhixing.config.RouteProperties());

        service.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).anyMatch(e -> e.startsWith("done:"));
        verify(quietNodeMapper, never()).insert(any(TraceNode.class));
        verify(quietRunMapper, never()).insert(any(TraceRun.class));
    }

    // ===== 阶段 2 新增用例 =====

    @Test
    void 多轮memory注入_messages含系统规则与历史且检索用独立问题与关键词() {
        QueryRewriter rewriter = (history, summary, q) ->
                new RewrittenQuery("Redmi K80 的电池容量是多少", List.of("K80", "电池"), true);
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.load(1L)).thenReturn(new MemoryView(null, List.of(
                new Turn("user", "15 Ultra 防水吗"), new Turn("assistant", "IP68 [1]。"))));
        when(memory.completedTurns(1L)).thenReturn(1);
        QaService service = newQaService(new TraceProperties(), memory,
                new Summarizer(fakeChat, new SummaryProperties()), rewriter, new RewriteProperties());

        service.runQa(request(), 1L, 100L, sink);

        // DL-14 ①：system 在首位 + 历史原样交替 + 末条 user = 资料块 + 独立问题
        ChatRequest sent = fakeChat.lastStreamRequest;
        assertThat(sent.getMessages()).hasSize(4);
        assertThat(sent.getMessages().get(0).role()).isEqualTo("system");
        assertThat(sent.getMessages().get(0).content()).contains("知识库问答助手");
        assertThat(sent.getMessages().get(1).role()).isEqualTo("user");
        assertThat(sent.getMessages().get(1).content()).isEqualTo("15 Ultra 防水吗");
        assertThat(sent.getMessages().get(2).role()).isEqualTo("assistant");
        assertThat(sent.getMessages().get(2).content()).isEqualTo("IP68 [1]。");
        assertThat(sent.getMessages().get(3).role()).isEqualTo("user");
        assertThat(sent.getMessages().get(3).content()).contains("[1] （来源：01-param-xiaomi-15-ultra.md）");
        assertThat(sent.getMessages().get(3).content()).endsWith("Redmi K80 的电池容量是多少");

        // 五参检索收到独立问题与关键词（keyword-channel 透传）
        verify(retrievalEngine).retrieve(eq(9L), eq("Redmi K80 的电池容量是多少"),
                eq(List.of("K80", "电池")), any(RetrieveBudget.class), any());
        // trace 新增 rewrite 节点
        ArgumentCaptor<TraceNode> nodeCaptor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper, times(2)).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getAllValues()).extracting(TraceNode::getName)
                .containsExactly("rewrite", "llm_chat");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 第9轮触发摘要_落库summary与summarizedMsgId且重写用新摘要() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite(anyList(), any(), any()))
                .thenReturn(new RewrittenQuery("独立问题", List.of(), false));
        Summarizer summarizer = mock(Summarizer.class);
        when(summarizer.roll(any(), anyList())).thenReturn("新摘要文本");
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.load(1L)).thenReturn(new MemoryView("旧摘要", List.of(
                new Turn("user", "q8"), new Turn("assistant", "a8"))));
        when(memory.completedTurns(1L)).thenReturn(9);
        when(memory.turnsBeforeWindow(1L)).thenReturn(List.of(
                new Turn("user", "u1"), new Turn("assistant", "a1"),
                new Turn("user", "u2"), new Turn("assistant", "a2"),
                new Turn("user", "u3"), new Turn("assistant", "a3")));
        QaService service = newQaService(new TraceProperties(), memory,
                summarizer, rewriter, new RewriteProperties());

        service.runQa(request(), 1L, 100L, sink);

        // 素材策略：窗口外更早轮次全量送入（这里 3 轮 < 4 轮上限）
        ArgumentCaptor<List<Turn>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(summarizer).roll(eq("旧摘要"), batchCaptor.capture());
        assertThat(batchCaptor.getValue()).extracting(Turn::content)
                .containsExactly("u1", "a1", "u2", "a2", "u3", "a3");
        // 落库：summary_text=新摘要、summarized_msg_id=本轮 user 消息 id
        ArgumentCaptor<QaSession> patchCaptor = ArgumentCaptor.forClass(QaSession.class);
        verify(sessionMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(patchCaptor.getValue().getSummaryText()).isEqualTo("新摘要文本");
        assertThat(patchCaptor.getValue().getSummarizedMsgId()).isEqualTo(100L);
        // DL-14 ② 同步语义：本轮重写即用新摘要
        verify(rewriter).rewrite(anyList(), eq("新摘要文本"), any());
        // summarize + rewrite + llm_chat 三节点（窗口非空，rewrite 亦触发）
        ArgumentCaptor<TraceNode> nodeCaptor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper, times(3)).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getAllValues()).extracting(TraceNode::getName)
                .containsExactly("summarize", "rewrite", "llm_chat");
    }

    @Test
    void 摘要落库失败_本轮仍用新摘要且链路正常() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        when(rewriter.rewrite(anyList(), any(), any()))
                .thenReturn(new RewrittenQuery("独立问题", List.of(), false));
        Summarizer summarizer = mock(Summarizer.class);
        when(summarizer.roll(any(), anyList())).thenReturn("新摘要文本");
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.load(1L)).thenReturn(new MemoryView("旧摘要", List.of(
                new Turn("user", "q8"), new Turn("assistant", "a8"))));
        when(memory.completedTurns(1L)).thenReturn(9);
        when(memory.turnsBeforeWindow(1L)).thenReturn(List.of(
                new Turn("user", "u1"), new Turn("assistant", "a1")));
        when(sessionMapper.updateById(any(QaSession.class))).thenThrow(new IllegalStateException("db write fail"));
        QaService service = newQaService(new TraceProperties(), memory, summarizer, rewriter, new RewriteProperties());

        service.runQa(request(), 1L, 100L, sink);

        // 对齐 saveAssistantQuietly 语义：落库失败只告警，本轮链路仍用新摘要（下轮触发自愈）
        verify(rewriter).rewrite(anyList(), eq("新摘要文本"), any());
        assertThat(sink.events.get(sink.events.size() - 2)).startsWith("sources:");
        assertThat(sink.events.get(sink.events.size() - 1)).startsWith("done:");
        // summarize 节点只包 roll：roll 成功 → 节点 SUCCESS（落库失败不记为摘要失败）
        ArgumentCaptor<TraceNode> nodeCaptor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper, times(3)).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getAllValues().stream().filter(n -> "summarize".equals(n.getName())))
                .allMatch(n -> "SUCCESS".equals(n.getStatus()));
    }

    @Test
    void 重写异常_降级用原问题直查且不终态() {
        QueryRewriter rewriter = (history, summary, q) -> {
            throw new IllegalStateException("rewrite llm down");
        };
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.load(1L)).thenReturn(new MemoryView(null, List.of(
                new Turn("user", "q1"), new Turn("assistant", "a1"))));
        when(memory.completedTurns(1L)).thenReturn(1);
        QaService service = newQaService(new TraceProperties(), memory,
                new Summarizer(fakeChat, new SummaryProperties()), rewriter, new RewriteProperties());

        service.runQa(request(), 1L, 100L, sink);

        // DL-14 ③ 降级不终态：检索用原问题 + 空关键词，终态仍是正常 sources+done
        verify(retrievalEngine).retrieve(eq(9L), eq(request().getQuestion()), eq(List.of()),
                any(RetrieveBudget.class), any());
        assertThat(sink.events.get(sink.events.size() - 2)).startsWith("sources:");
        assertThat(sink.events.get(sink.events.size() - 1)).startsWith("done:");
        // rewrite 节点记 FAILED，llm_chat 照常
        ArgumentCaptor<TraceNode> nodeCaptor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper, times(2)).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getAllValues().get(0).getName()).isEqualTo("rewrite");
        assertThat(nodeCaptor.getAllValues().get(0).getStatus()).isEqualTo("FAILED");
        assertThat(nodeCaptor.getAllValues().get(1).getName()).isEqualTo("llm_chat");
        assertThat(nodeCaptor.getAllValues().get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void 开关关闭_完整回到阶段1行为() {
        SessionMemory memory = mock(SessionMemory.class);
        Summarizer summarizer = mock(Summarizer.class);
        QueryRewriter rewriter = mock(QueryRewriter.class);
        RewriteProperties off = new RewriteProperties();
        off.setEnabled(false);
        QaService service = newQaService(new TraceProperties(), memory, summarizer, rewriter, off);

        service.runQa(request(), 1L, 100L, sink);

        // 无记忆、无摘要、无重写
        verify(memory, never()).load(anyLong());
        verify(summarizer, never()).roll(any(), anyList());
        verify(rewriter, never()).rewrite(anyList(), any(), any());
        // 阶段 1 的 4 参检索契约
        verify(retrievalEngine).retrieve(anyLong(), any(), any(RetrieveBudget.class), any());
        // 单 prompt rag-v0 形态（模板原文开头 + 资料块 + 问题）
        ChatRequest sent = fakeChat.lastStreamRequest;
        assertThat(sent.getMessages()).hasSize(1);
        assertThat(sent.getMessages().get(0).role()).isEqualTo("user");
        assertThat(sent.getMessages().get(0).content()).startsWith("你是知识库问答助手");
        assertThat(sent.getMessages().get(0).content()).contains("【参考资料】").contains(request().getQuestion());
    }

    // ===== 长期记忆（R-T4 §2.3）=====

    @Test
    void 有长期记忆注入_多轮分支system后紧跟user偏好块() {
        com.zhixing.core.memory.LongTermMemoryService longTermMemory =
                mock(com.zhixing.core.memory.LongTermMemoryService.class);
        when(longTermMemory.injectionBlock()).thenReturn("【已知用户偏好】\n- 用户偏好深色模式");
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.load(1L)).thenReturn(new MemoryView(null, List.of(
                new Turn("user", "q1"), new Turn("assistant", "a1"))));
        when(memory.completedTurns(1L)).thenReturn(1);
        QaService service = newQaService(new TraceProperties(), memory,
                new Summarizer(fakeChat, new SummaryProperties()), new NoopQueryRewriter(),
                new RewriteProperties(), longTermMemory);

        service.runQa(request(), 1L, 100L, sink);

        // messages：system → user(偏好块) → user(q1) → assistant(a1) → user(资料+问题)
        ChatRequest sentReq = fakeChat.lastStreamRequest;
        assertThat(sentReq.getMessages()).hasSize(5);
        assertThat(sentReq.getMessages().get(0).role()).isEqualTo("system");
        assertThat(sentReq.getMessages().get(1).role()).isEqualTo("user");
        assertThat(sentReq.getMessages().get(1).content()).isEqualTo("【已知用户偏好】\n- 用户偏好深色模式");
        // 收尾触发：本轮 user 问题与 assistant 回答进入仲裁
        verify(longTermMemory).arbitrateQuietlyAsync(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(request().getQuestion()),
                org.mockito.ArgumentMatchers.eq("小米 15 Ultra 搭载6000mAh 电池 [1]。"));
    }

    @Test
    void 无长期记忆_qa链路事件序不破且零额外消息() {
        // setUp 默认 memoryStub()：injectionBlock=null、触发 no-op——回归断言与阶段 2 完全一致
        qaService.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).hasSize(5);
        assertThat(sink.events.get(0)).startsWith("start:");
        assertThat(sink.events.get(4)).startsWith("done:");
        assertThat(fakeChat.lastStreamRequest.getMessages()).hasSize(2);   // system + 末条 user（空窗口无历史）
    }
}
