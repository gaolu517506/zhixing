package com.zhixing.service;

import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.AgentRunContext;
import com.zhixing.core.agent.ConfirmManager;
import com.zhixing.core.agent.PendingToolCall;
import com.zhixing.core.agent.ReActEngine;
import com.zhixing.core.agent.ToolExecutor;
import com.zhixing.core.agent.ToolRegistry;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * trace 节点 seq 全 run 严格递增（v1.1 修复④，任务书 T5-6）：真库实证同一 run 的
 * confirm 续跑段 seq 重复从 1 起（2026-10-26，如 run 2114658485683064832 三节点全 1）。
 * 锁死两点：① pending 工具节点与续跑引擎收到同一个 TraceContext；② 计数器从挂起前
 * 首段已落节点的 max(seq) 续起，不再各自归零。
 */
class AgentServiceTraceSeqTest {

    private ReActEngine engine;

    private AgentRunMapper runMapper;

    private ConfirmManager confirmManager;

    private ToolExecutor toolExecutor;

    private ToolRegistry toolRegistry;

    private TraceNodeMapper traceNodeMapper;

    private TraceRecorder traceRecorder;

    private AgentService service;

    @BeforeEach
    void setUp() {
        engine = mock(ReActEngine.class);
        runMapper = mock(AgentRunMapper.class);
        confirmManager = mock(ConfirmManager.class);
        toolExecutor = mock(ToolExecutor.class);
        toolRegistry = mock(ToolRegistry.class);
        traceNodeMapper = mock(TraceNodeMapper.class);
        // 真实 TraceRecorder + 桩 max(seq) 查询：首段已落 llm/tool/llm 三节点（seq 1..3）
        traceRecorder = new TraceRecorder(mock(TraceRunMapper.class), traceNodeMapper,
                new com.zhixing.config.TraceProperties());

        AgentRun suspended = new AgentRun();
        suspended.setId(77L);
        suspended.setSessionId(1L);
        suspended.setKbId(9L);
        suspended.setInput("删掉它");
        suspended.setStatus("WAITING_CONFIRM");
        suspended.setIterations(1);
        suspended.setMessagesSnapshot("[]");
        suspended.setPendingTool("{\"toolCallId\":\"call-1\",\"name\":\"delete_document\",\"arguments\":\"{}\"}");
        when(confirmManager.loadResumable(77L)).thenReturn(suspended);
        when(confirmManager.deserializeMessages("[]")).thenReturn(new ArrayList<>());
        when(confirmManager.deserializePendingTool(any()))
                .thenReturn(new PendingToolCall("call-1", "delete_document", "{}"));
        when(toolRegistry.find("delete_document")).thenReturn(mock(com.zhixing.core.agent.Tool.class));
        when(toolExecutor.execute(any(), any(ToolCall.class), anyLong(), any(), anyInt()))
                .thenReturn(ToolResult.ok("已删除文档"));
        when(runMapper.resumeFromConfirm(77L)).thenReturn(1);

        service = new AgentService(engine, confirmManager, toolRegistry,
                toolExecutor, runMapper, mock(QaSessionMapper.class),
                mock(QaMessageMapper.class), new AgentProperties(), Runnable::run,
                mock(com.zhixing.core.memory.LongTermMemoryService.class),
                traceRecorder);
    }

    @Test
    void confirm续跑段seq从首段max续起且全程同一TraceContext() {
        doReturn(List.of(3)).when(traceNodeMapper).selectObjs(any());

        service.confirm(77L, true);

        // ① pending 工具执行收到的 TraceContext 计数器钉在 3——其节点 seq=4，不再从 1 起
        ArgumentCaptor<TraceContext> toolCtx = ArgumentCaptor.forClass(TraceContext.class);
        verify(toolExecutor).execute(any(), any(ToolCall.class), anyLong(), toolCtx.capture(), anyInt());
        assertThat(toolCtx.getValue().seq().get()).isEqualTo(3);

        // ② 续跑引擎收到的正是同一个 TraceContext 实例（一个 run 一个 TraceContext 贯穿）
        ArgumentCaptor<AgentRunContext> runCtx = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(runCtx.capture(), any(), any());
        assertThat(runCtx.getValue().traceCtx()).isSameAs(toolCtx.getValue());
    }

    @Test
    void 无首段节点的续跑从空计数器起步且拒绝路径同样贯穿() {
        doReturn(List.of()).when(traceNodeMapper).selectObjs(any());

        // 拒绝路径不执行工具，但 TraceContext 同样 openContext 打开并贯穿进续跑引擎
        service.confirm(77L, false);

        ArgumentCaptor<AgentRunContext> runCtx = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(runCtx.capture(), any(), any());
        assertThat(runCtx.getValue().traceCtx().seq().get()).isZero();
        assertThat(runCtx.getValue().traceCtx().nextSeq()).isEqualTo(1);
    }
}
