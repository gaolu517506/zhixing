package com.zhixing.controller;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 运行请求（概设 §6）：{sessionId?, kbId, input}。
 */
@Data
public class AgentRunRequest {

    /** 为空则新建会话（与 QA 共享 qa_session）。 */
    private Long sessionId;

    private Long kbId;

    @NotBlank(message = "input 不能为空")
    private String input;
}
