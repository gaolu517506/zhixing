package com.zhixing.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * POST /api/qa/stream 请求体（详设 §6 契约）。
 */
@Data
public class QaStreamRequest {

    @NotNull(message = "kbId 不能为空")
    private Long kbId;

    /** 为空则新建会话（标题 = 问题前 20 字）。 */
    private Long sessionId;

    @NotBlank(message = "question 不能为空")
    private String question;
}
