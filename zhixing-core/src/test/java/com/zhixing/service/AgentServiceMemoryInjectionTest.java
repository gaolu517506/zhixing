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
import org.junit.jupiter.api.AfterEach;
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
 * Agent 长期记忆注入单测（评审 P7 / 任务书 v1.1-D ③）：记忆块为独立 user 消息且紧跟
 * system（DL-17 ② 与 QA 侧同序），不再拼进 system 尾部；会话历史回放位于当前输入之前。
 * 消息序断言：system → [记忆 user] → 历史 user/assistant → 当前输入。桩化离线。
 */
class AgentServiceMemoryInjectionTest {

    private ReActEngine engine;

    private QaMessageMapper messageMapper;

    private com.zhixing.core.memory.LongTermMemoryService longTermMemory;

    private AgentService service;

    @AfterEach
    void clearAuth() {
        com.zhixing.common.auth.AuthContext.clear();
    }

    @BeforeEach
    void setUp() {
        com.zhixing.common.auth.AuthContext.set(
                new com.zhixing.common.auth.CurrentUser(1L, "tester", "USER", com.zhixing.common.auth.AuthChannel.WEB));
        engine = mock(ReActEngine.class);
        messageMapper = mock(QaMessageMapper.class);
        longTermMemory = mock(com.zhixing.core.memory.LongTermMemoryService.class);
        QaSessionMapper sessionMapper = mock(QaSessionMapper.class);
        com.zhixing.dao.entity.QaSession existing = new com.zhixing.dao.entity.QaSession();
        existing.setId(77L);
        when(sessionMapper.selectById(77L)).thenReturn(existing);
        service = new AgentService(engine, mock(ConfirmManager.class), mock(ToolRegistry.class),
                mock(ToolExecutor.class), mock(AgentRunMapper.class), sessionMapper,
                messageMapper, new AgentProperties(), Runnable::run, longTermMemory,
                mock(com.zhixing.infra.trace.TraceRecorder.class),
                mock(PermissionService.class), mock(AuditRecorder.class));
    }

    private AgentRunRequest request() {
        AgentRunRequest req = new AgentRunRequest();
        req.setSessionId(77L);
        req.setKbId(9L);
        req.setInput("帮我把 K80 那篇文档删掉");
        return req;
    }

    private List<com.zhixing.infra.llm.dto.ChatMessage> capturedMessages() {
        ArgumentCaptor<AgentRunContext> ctx = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(engine).run(ctx.capture(), any(AgentCancellation.class), any());
        return ctx.getValue().messages();
    }

    @Test
    void 有长期记忆_独立user消息紧跟system() {
        when(longTermMemory.injectionBlock()).thenReturn("【已知用户偏好】\n- 用户偏好深色模式");
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);

        service.run(request());

        var messages = capturedMessages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).startsWith("你是知行知识库的智能助手");
        // 评审 P7 的病灶断言：偏好块不再拼进 system 内容尾部
        assertThat(messages.get(0).content()).doesNotContain("【已知用户偏好】");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("【已知用户偏好】\n- 用户偏好深色模式");
        assertThat(messages.get(2).role()).isEqualTo("user");
        assertThat(messages.get(2).content()).isEqualTo("帮我把 K80 那篇文档删掉");
        // 收尾触发以本轮 user 输入进入仲裁
        verify(longTermMemory).arbitrateQuietlyAsync(org.mockito.ArgumentMatchers.eq(77L),
                org.mockito.ArgumentMatchers.eq("帮我把 K80 那篇文档删掉"), any());
    }

    @Test
    void 无长期记忆_system为原文不加块() {
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);

        service.run(request());

        var messages = capturedMessages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).doesNotContain("【已知用户偏好】");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).isEqualTo("帮我把 K80 那篇文档删掉");
    }

    @Test
    void 有会话历史_消息序为system记忆历史当前输入() {
        when(longTermMemory.injectionBlock()).thenReturn("【已知用户偏好】\n- 偏好简洁回答");
        QaMessage olderUser = new QaMessage();
        olderUser.setRole("user");
        olderUser.setContent("知识库里有没有 K80 电池的信息？");
        QaMessage olderAssistant = new QaMessage();
        olderAssistant.setRole("assistant");
        olderAssistant.setContent("有，K80 那篇文档提到…");
        // mapper 按 id 倒序返回（服务内 reverse 成正序回放）
        when(messageMapper.selectList(any())).thenReturn(List.of(olderAssistant, olderUser));
        when(messageMapper.insert(any(QaMessage.class))).thenReturn(1);

        service.run(request());

        var messages = capturedMessages();
        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(messages.get(1).content()).startsWith("【已知用户偏好】");
        assertThat(messages.get(2).role()).isEqualTo("user");
        assertThat(messages.get(2).content()).isEqualTo("知识库里有没有 K80 电池的信息？");
        assertThat(messages.get(3).role()).isEqualTo("assistant");
        assertThat(messages.get(4).role()).isEqualTo("user");
        assertThat(messages.get(4).content()).isEqualTo("帮我把 K80 那篇文档删掉");
    }
}
