package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhixing.dao.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行（详设 13-阶段3 §3）。挂起恢复的全部状态在 messages_snapshot + pending_tool
 * 两个 JSONB 列——进程重启后 confirm 依然可用（概设 D5 的核心论点）。
 * status：RUNNING / WAITING_CONFIRM / COMPLETED / FAILED / CANCELLED。
 */
@Data
@TableName(value = "agent_run", autoResultMap = true)
public class AgentRun {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 复用 qa_session（qa_message.engine='agent' 区分），可为空。 */
    private Long sessionId;

    private Long kbId;

    /** 发起用户（V4，F3 审计的操作者维度）；v2.0 起新 run 必填，存量行为 null。 */
    private Long userId;

    private String input;

    private String status;

    private Integer iterations;

    /** 全量 messages 序列化 JSON（挂起恢复用，含 system 与 assistant toolCalls 消息）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String messagesSnapshot;

    /** {toolCallId, name, arguments} JSON。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String pendingTool;

    private String errorMsg;

    private Integer promptTokens;

    private Integer completionTokens;

    private Long costMs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
