package com.zhixing.service;

import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.AgentCancellation;
import com.zhixing.core.agent.AgentRunContext;
import com.zhixing.core.agent.ConfirmManager;
import com.zhixing.core.agent.ReActEngine;
import com.zhixing.core.agent.ToolExecutor;
import com.zhixing.core.agent.ToolRegistry;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 长期记忆注入单测（任务书 §3「注入：QA/Agent 有无记忆两形态」）：
 * 有记忆 → system 消息尾部追加偏好块；无记忆 → system 原文。桩化离线。
 */
class AgentServiceMemoryInjectionTest {

    private ReActEngine engine;

    private QaMessageMapper messageMapper;

    private com.zhixing.core.memory.LongTermMemoryService longTermMemory;

    private AgentService service;

    @BeforeEach
    void setUp() {
        engine = mock(ReActEngine.class);
        messageMapper = mock(QaMessageMapper.class);
        longTermMemory = mock(com.zhixing.core.memory.LongTermMemoryService.class);
        QaSessionMapper sessionMapper = mock(QaSessionMapper.class);
        com.zhixing.dao.entity.QaSession existing = new com.zhixing.dao.entity.QaSession();
        existing.setId(77L);
        when(sessionMapper.selectById(77L)).thenReturn(existing);
        service = new AgentService(engine, mock(ConfirmManager.class), mock(ToolRegistry.class),
                mock(ToolExecutor.class), mock(AgentRunMapper.class), sessionMapper,
                messageMapper, new AgentProperties(), Runnable::run, longTermMemory);
    }

    private AgentRunRequest request() {
        AgentRunRequest req = new AgentRunRequest();
        req.setSessionId(77L);
        req.setKbId(9L);
        req.setInput("帮我把 K80 那篇文档删掉");
        return req;
    }

    @Test
    void 有长期记忆_system消息尾部追加偏好块() {
        when(longTermMemory.injectionBlock()).thenReturn("【已知用户偏好】\n- 用户偏好深色模式");
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);

        service.run(request());

        ArgumentCaptor<AgentRunContext> ctx = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(ctx.capture(), any(AgentCancellation.class), any());
        var messages = ctx.getValue().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).startsWith("你是知行知识库的智能助手");
        assertThat(messages.get(0).content()).endsWith("【已知用户偏好】\n- 用户偏好深色模式");
        // 收尾触发以本轮 user 输入进入仲裁
        verify(longTermMemory).arbitrateQuietlyAsync(org.mockito.ArgumentMatchers.eq(77L),
                org.mockito.ArgumentMatchers.eq("帮我把 K80 那篇文档删掉"), any());
    }

    @Test
    void 无长期记忆_system为原文不加块() {
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);

        service.run(request());

        ArgumentCaptor<AgentRunContext> ctx = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(ctx.capture(), any(AgentCancellation.class), any());
        var messages = ctx.getValue().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).doesNotContain("【已知用户偏好】");
    }
}
