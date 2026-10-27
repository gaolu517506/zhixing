package com.zhixing.service;

import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.AgentRunContext;
import com.zhixing.core.agent.ReActEngine;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 会话历史回放（v1.1）：此前跨 run 完全失忆，「执行删除」这类续指令在新 run 里无从理解
 * （2026-10-26 用户实测暴露）。回放近 16 条 user/assistant 文本，当前输入（已先入库）排除不重复。
 */
class AgentServiceHistoryReplayTest {

    private ReActEngine engine;

    private QaMessageMapper messageMapper;

    private AgentService service;

    @BeforeEach
    void setUp() {
        engine = mock(ReActEngine.class);
        messageMapper = mock(QaMessageMapper.class);
        QaSessionMapper sessionMapper = mock(QaSessionMapper.class);
        com.zhixing.dao.entity.QaSession existing = new com.zhixing.dao.entity.QaSession();
        existing.setId(77L);
        when(sessionMapper.selectById(77L)).thenReturn(existing);

        service = new AgentService(engine, mock(com.zhixing.core.agent.ConfirmManager.class),
                mock(com.zhixing.core.agent.ToolRegistry.class), mock(com.zhixing.core.agent.ToolExecutor.class),
                mock(AgentRunMapper.class), sessionMapper, messageMapper,
                new AgentProperties(), Runnable::run,
                mock(com.zhixing.core.memory.LongTermMemoryService.class),
                mock(com.zhixing.infra.trace.TraceRecorder.class));
    }

    private QaMessage message(long id, String role, String content) {
        QaMessage m = new QaMessage();
        m.setId(id);
        m.setSessionId(77L);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void 历史回放在当前输入之前且当前输入不重复() {
        // 昨天的对话 + 今天刚入库的当前输入（id=300）：回放应含前者、排除后者
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(300, "user", "删除演示文档"),
                message(101, "assistant", "已找到该文档，内容是充电注意事项"),
                message(100, "user", "查一下演示文档")));

        AgentRunRequest req = new AgentRunRequest();
        req.setSessionId(77L);
        req.setKbId(9L);
        req.setInput("执行删除");
        service.run(req);

        ArgumentCaptor<AgentRunContext> captor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(captor.capture(), any(), any());
        List<com.zhixing.infra.llm.dto.ChatMessage> messages = captor.getValue().messages();

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("查一下演示文档");
        assertThat(messages.get(2).role()).isEqualTo("assistant");
        assertThat(messages.get(2).content()).isEqualTo("已找到该文档，内容是充电注意事项");
        assertThat(messages.get(3).role()).isEqualTo("user");
        assertThat(messages.get(3).content()).isEqualTo("删除演示文档");
        assertThat(messages.get(4).role()).isEqualTo("user");
        assertThat(messages.get(4).content()).isEqualTo("执行删除");
    }

    @Test
    void 首问无历史时仅系统与当前输入() {
        // 首问：真实查询（排除当前输入 id）返回空——stub 即查询结果，不含当前输入行
        when(messageMapper.selectList(any())).thenReturn(List.of());

        AgentRunRequest req = new AgentRunRequest();
        req.setSessionId(77L);
        req.setKbId(9L);
        req.setInput("你好");
        service.run(req);

        ArgumentCaptor<AgentRunContext> captor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(captor.capture(), any(), any());
        List<com.zhixing.infra.llm.dto.ChatMessage> messages = captor.getValue().messages();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("你好");
    }
}
