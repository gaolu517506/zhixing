package com.zhixing.service;

import com.zhixing.common.BusinessException;
import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.AgentRunContext;
import com.zhixing.core.agent.ConfirmManager;
import com.zhixing.core.agent.PendingToolCall;
import com.zhixing.core.agent.ReActEngine;
import com.zhixing.core.agent.ToolExecutor;
import com.zhixing.core.agent.ToolRegistry;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * confirm 的 CAS 抢占（P0，全量评审 20261027）：写工具的单次执行保证由
 * resumeFromConfirm 的影响行数守卫承担——0 行 = 名额已被占用，必须拒绝而非执行。
 */
class AgentServiceConfirmCasTest {

    private ReActEngine engine;

    private AgentRunMapper runMapper;

    private ConfirmManager confirmManager;

    private ToolExecutor toolExecutor;

    private ToolRegistry toolRegistry;

    private AgentService service;

    @BeforeEach
    void setUp() {
        engine = mock(ReActEngine.class);
        runMapper = mock(AgentRunMapper.class);
        confirmManager = mock(ConfirmManager.class);
        toolExecutor = mock(ToolExecutor.class);
        toolRegistry = mock(ToolRegistry.class);

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

        service = new AgentService(engine, confirmManager, toolRegistry,
                toolExecutor, runMapper, mock(QaSessionMapper.class),
                mock(QaMessageMapper.class), new AgentProperties(), Runnable::run,
                mock(com.zhixing.core.memory.LongTermMemoryService.class),
                mock(com.zhixing.infra.trace.TraceRecorder.class),
                mock(PermissionService.class), mock(AuditRecorder.class));
    }

    @Test
    void cas成功才执行写工具并续跑() {
        when(runMapper.resumeFromConfirm(77L)).thenReturn(1);

        service.confirm(77L, true);

        verify(toolExecutor).execute(any(), any(ToolCall.class), anyLong(), any(), anyInt());
        verify(engine).run(any(AgentRunContext.class), any(), any());
    }

    @Test
    void cas零行拒绝且绝不执行写工具() {
        // 并发第二个 confirm：状态已被首个翻走，影响行数 0 —— 这是单次执行保证的闸门
        when(runMapper.resumeFromConfirm(77L)).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(77L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被处理");

        verify(toolExecutor, never()).execute(any(), any(ToolCall.class), anyLong(), any(), anyInt());
        verify(engine, never()).run(any(AgentRunContext.class), any(), any());
    }

    @Test
    void 拒绝路径同样先过cas且不执行工具() {
        when(runMapper.resumeFromConfirm(77L)).thenReturn(1);

        service.confirm(77L, false);

        verify(toolExecutor, never()).execute(any(), any(ToolCall.class), anyLong(), any(), anyInt());
        verify(engine).run(any(AgentRunContext.class), any(), any());
    }

    @Test
    void approve执行抛异常也回填错误结果续跑() {
        when(runMapper.resumeFromConfirm(77L)).thenReturn(1);
        when(toolExecutor.execute(any(), any(ToolCall.class), anyLong(), any(), anyInt()))
                .thenThrow(new IllegalStateException("池已关闭"));

        service.confirm(77L, true);

        // 执行异常不中断续跑流（CAS 已占用名额，必须给模型回填结果）
        ArgumentCaptor<AgentRunContext> captor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(captor.capture(), any(), any());
        List<com.zhixing.infra.llm.dto.ChatMessage> sent = captor.getValue().messages();
        assertThat(sent.get(sent.size() - 1).content()).contains("工具执行异常");
    }
}
