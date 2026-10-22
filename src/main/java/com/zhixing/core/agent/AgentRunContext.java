package com.zhixing.core.agent;

import com.zhixing.infra.llm.dto.ChatMessage;

import java.util.List;

/**
 * 引擎单次（续）运行的输入。messages 必须是可变列表——引擎按轮回填 assistant/tool 消息；
 * startIteration 支持挂起恢复后延续计数（DL-15 ② 的 maxIterations 判定基于它）。
 */
public record AgentRunContext(long runId, Long sessionId, Long kbId,
                              List<ChatMessage> messages, int startIteration) {
}
