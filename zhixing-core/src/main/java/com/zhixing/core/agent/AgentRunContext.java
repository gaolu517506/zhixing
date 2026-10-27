package com.zhixing.core.agent;

import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.trace.TraceContext;

import java.util.List;

/**
 * 引擎单次（续）运行的输入。messages 必须是可变列表——引擎按轮回填 assistant/tool 消息；
 * startIteration 支持挂起恢复后延续计数（DL-15 ② 的 maxIterations 判定基于它）。
 * traceCtx 由 AgentService 以 TraceRecorder.openContext 打开后贯穿传递（v1.1 修复④：
 * 一个 run 一个 TraceContext——首段 / confirm pending 工具 / 续跑段共用同一计数器，
 * 节点 seq 全 run 严格递增；引擎不再自建）。必填，不可为 null。
 */
public record AgentRunContext(long runId, Long sessionId, Long kbId,
                              List<ChatMessage> messages, int startIteration, TraceContext traceCtx) {
}
