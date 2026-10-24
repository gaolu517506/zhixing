package com.zhixing.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 确认挂起/恢复状态机（详设 13-阶段3 §8）。挂起的全部状态持久化在 agent_run：
 * 进程重启后 confirm 依然可用——对比「同连接阻塞等待」的不可恢复方案（概设 D5）。
 */
@Slf4j
@Component
public class ConfirmManager {

    private final AgentRunMapper runMapper;

    private final ObjectMapper objectMapper;

    public ConfirmManager(AgentRunMapper runMapper, ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 挂起：全量 messages 快照 + pending_tool 落库，status=WAITING_CONFIRM。
     * 此时本轮 assistant(toolCalls) 已在 messages 中；被挂起工具的结果消息不入列——
     * 续跑时按 approve/reject 补写后再继续循环。
     */
    public void suspend(long runId, List<ChatMessage> messages, ToolCall pending, int iterations) {
        try {
            AgentRun patch = new AgentRun();
            patch.setId(runId);
            patch.setStatus("WAITING_CONFIRM");
            patch.setIterations(iterations);
            patch.setMessagesSnapshot(objectMapper.writeValueAsString(messages));
            patch.setPendingTool(objectMapper.writeValueAsString(
                    new PendingToolCall(pending.id(), pending.name(), pending.arguments())));
            patch.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(patch);
        } catch (Exception e) {
            throw new IllegalStateException("挂起快照落库失败 runId=" + runId, e);
        }
    }

    /**
     * 恢复加载：status 必须 = WAITING_CONFIRM，否则拒绝（非法状态/不存在均为客户端误用）。
     * 错误码说明：Q 段现无适配码且 common/ 本线禁改，暂用 A0000 + 文案，已报 lead 裁决。
     */
    public AgentRun loadResumable(long runId) {
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "Agent run 不存在：" + runId);
        }
        if (!"WAITING_CONFIRM".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR,
                    "当前状态不允许确认操作：" + run.getStatus());
        }
        return run;
    }

    public List<ChatMessage> deserializeMessages(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, new TypeReference<List<ChatMessage>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("messages_snapshot 反序列化失败", e);
        }
    }

    public PendingToolCall deserializePendingTool(String json) {
        try {
            return objectMapper.readValue(json, PendingToolCall.class);
        } catch (Exception e) {
            throw new IllegalStateException("pending_tool 反序列化失败", e);
        }
    }
}
