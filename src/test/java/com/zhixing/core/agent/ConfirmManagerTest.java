package com.zhixing.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认挂起/恢复状态机单测（任务书 §4）：挂起快照完整性与回读一致、非法状态拒绝。
 */
class ConfirmManagerTest {

    private AgentRunMapper runMapper;

    private ConfirmManager manager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        manager = new ConfirmManager(runMapper, objectMapper);
    }

    @Test
    void 挂起_快照全量messages与pending_tool落库且状态WAITING_CONFIRM() throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("系统规则"),
                ChatMessage.user("把 K80 那篇删了"),
                ChatMessage.assistantWithToolCalls(
                        List.of(new ToolCall("call-d", "delete_document", "{\"documentId\":123}"))));
        ToolCall pending = messages.get(2).toolCalls().get(0);

        manager.suspend(1L, messages, pending, 3);

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).updateById(captor.capture());
        AgentRun patch = captor.getValue();
        assertThat(patch.getId()).isEqualTo(1L);
        assertThat(patch.getStatus()).isEqualTo("WAITING_CONFIRM");
        assertThat(patch.getIterations()).isEqualTo(3);

        // 快照回读与原 messages 逐条一致（含 assistant 的 toolCalls 结构）
        List<ChatMessage> restored = manager.deserializeMessages(patch.getMessagesSnapshot());
        assertThat(restored).hasSize(3);
        assertThat(restored.get(2).role()).isEqualTo("assistant");
        assertThat(restored.get(2).toolCalls()).hasSize(1);
        assertThat(restored.get(2).toolCalls().get(0).name()).isEqualTo("delete_document");
        assertThat(restored.get(2).toolCalls().get(0).arguments()).isEqualTo("{\"documentId\":123}");

        PendingToolCall restoredPending = manager.deserializePendingTool(patch.getPendingTool());
        assertThat(restoredPending).isEqualTo(new PendingToolCall("call-d", "delete_document", "{\"documentId\":123}"));
    }

    @Test
    void 恢复加载_run不存在_拒绝() {
        when(runMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> manager.loadResumable(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 恢复加载_状态非WAITING_CONFIRM_拒绝() {
        AgentRun running = new AgentRun();
        running.setId(1L);
        running.setStatus("RUNNING");
        when(runMapper.selectById(1L)).thenReturn(running);

        assertThatThrownBy(() -> manager.loadResumable(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许确认");
    }

    @Test
    void 恢复加载_状态正确_返回run() {
        AgentRun waiting = new AgentRun();
        waiting.setId(1L);
        waiting.setStatus("WAITING_CONFIRM");
        when(runMapper.selectById(1L)).thenReturn(waiting);

        assertThat(manager.loadResumable(1L)).isSameAs(waiting);
    }
}
