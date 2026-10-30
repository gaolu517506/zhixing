package com.zhixing.service;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * POST /api/qa/stream 请求体（详设 §6 契约 + F11 线 G additive）。
 * kbId 自 M3 起可空：缺省时走意图路由（02 §B10，zhixing.route.enabled=true），
 * 命中规则目标 ∩ 有权限库；路由关闭或未命中回落时报 A0000「请先选择知识库」。
 */
@Data
public class QaStreamRequest {

    /** 可空：缺省 = 由意图路由决定目标库（多库场景免选库直接提问，PRD 用户故事 42）。 */
    private Long kbId;

    /** 为空则新建会话（标题 = 问题前 20 字）。 */
    private Long sessionId;

    @NotBlank(message = "question 不能为空")
    private String question;
}
