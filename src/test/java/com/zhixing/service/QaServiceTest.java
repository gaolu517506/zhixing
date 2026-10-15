package com.zhixing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.QaProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.config.TraceProperties;
import com.zhixing.controller.QaStreamRequest;
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
import com.zhixing.infra.trace.TraceRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA 链路单测（任务书 §3）：fake ChatClient（固定流）+ fake RetrievalEngine + mock mapper，
 * 断言 SSE 事件序 start→content(s)→sources→done、异常路径 error+done、消息落库字段。全程不联网。
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

    /** 固定行为 fake：默认两段 delta + stop 终态（带 usage）；测试按需改 deltas / failureAfterDeltas。 */
    static class FakeChatClient implements ChatClient {
        List<String> deltas = List.of("小米 15 Ultra 搭载", "6000mAh 电池 [1]。");
        RuntimeException failureAfterDeltas;
        final ChatUsage usage = new ChatUsage(18, 10);

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

    @BeforeEach
    void setUp() {
        sessionMapper = mock(QaSessionMapper.class);
        messageMapper = mock(QaMessageMapper.class);
        traceRunMapper = mock(TraceRunMapper.class);
        traceNodeMapper = mock(TraceNodeMapper.class);
        retrievalEngine = mock(RetrievalEngine.class);
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);
        when(sessionMapper.insert(any(QaSession.class))).thenReturn(1);
        fakeChat = new FakeChatClient();

        CitedChunk hit = new CitedChunk(9001L, 101L, "01-param-xiaomi-15-ultra.md", "小米15 Ultra 电池 6000mAh", 0.032);
        when(retrievalEngine.retrieve(anyLong(), any(), any(RetrieveBudget.class), any()))
                .thenReturn(new RetrievalResult(List.of(hit), List.of(hit), List.of(hit)));

        sink = new RecordingSink();
        qaService = newQaService(new TraceProperties());
    }

    private QaService newQaService(TraceProperties traceProps) {
        return new QaService(fakeChat, retrievalEngine, sessionMapper, messageMapper,
                new TraceRecorder(traceRunMapper, traceNodeMapper, traceProps),
                new RetrieveProperties(), new QaProperties(), new ObjectMapper(), Runnable::run);
    }

    private static QaStreamRequest request() {
        QaStreamRequest req = new QaStreamRequest();
        req.setKbId(9L);
        req.setQuestion("小米15 Ultra 的电池容量是多少？官方数据给出实测续航表现");
        return req;
    }

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

        // trace 节点：vector/keyword/fusion/rerank 四节点由真实 RetrievalEngine 记录（本测试 mock 掉引擎），
        // 这里只验证 QaService 自己埋的 llm_chat 节点 SUCCESS——引擎四节点见 RetrievalEngineTest
        ArgumentCaptor<TraceNode> nodeCaptor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper).insert(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getName()).isEqualTo("llm_chat");
        assertThat(nodeCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void 检索异常_事件序start_error_done且不落assistant消息() {
        when(retrievalEngine.retrieve(anyLong(), any(), any(RetrieveBudget.class), any()))
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
    void trace关闭时_链路正常且零trace写入() {
        TraceProperties off = new TraceProperties();
        off.setEnabled(false);
        TraceNodeMapper quietNodeMapper = mock(TraceNodeMapper.class);
        TraceRunMapper quietRunMapper = mock(TraceRunMapper.class);
        QaService service = new QaService(fakeChat, retrievalEngine, sessionMapper, messageMapper,
                new TraceRecorder(quietRunMapper, quietNodeMapper, off),
                new RetrieveProperties(), new QaProperties(), new ObjectMapper(), Runnable::run);

        service.runQa(request(), 1L, 100L, sink);

        assertThat(sink.events).anyMatch(e -> e.startsWith("done:"));
        verify(quietNodeMapper, never()).insert(any(TraceNode.class));
        verify(quietRunMapper, never()).insert(any(TraceRun.class));
    }
}
