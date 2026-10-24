package com.zhixing.core.agent;

import cn.hutool.core.util.IdUtil;
import com.zhixing.config.AgentProperties;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.entity.AgentRunStep;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.AgentRunStepMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ToolDefinition;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.llm.ModelClientException;
import com.zhixing.infra.sse.SseEventSink;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 循环状态机（详设 13-阶段3 §5，本阶段最核心类）。
 *
 * <p>每轮：取消检查 → 水位检查（降级不终态）→ LLM 流式（thinking/content 外发，
 * tool_calls 经聚合回调）→ 分支：stop = 最终回答落库收工；tool_calls = assistant 消息原样
 * 入列 + 逐工具处理（读工具结果按 toolCallId 配对紧随回填；写工具挂起结束本流）；
 * maxIterations 耗尽注入提示强制无工具作答（DL-15 ②）。
 *
 * <p>三条不变式：
 * ① 消息回填顺序——assistant(toolCalls) 原样入列，每个 tool 结果按 toolCallId 配对紧随，
 *    顺序错一个供应商即 400（单测以 messages 快照精确断言）；
 * ② 工具失败不终态——一律错误结果回填让模型自纠，连续同名失败 ≥3 注入 user 提示防死循环；
 * ③ 取消/异常不吞内容——已生成 content 落 qa_message 后才发终态事件（终态成对规则）。
 */
@Slf4j
@Component
public class ReActEngine {

    /** LLM 流任务独立池：引擎任务阻塞等待流结束，若同池会整池饿死（检索池同构教训）。 */
    private static final ExecutorService LLM_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-llm-");
        t.setDaemon(true);
        return t;
    });

    private static final int STEP_TEXT_MAX = 2000;

    private static final int TOOL_RESULT_EVENT_MAX = 500;

    private static final int CONSECUTIVE_FAILURE_LIMIT = 3;

    static final String FORCE_FINAL_HINT = "已达工具调用上限，请基于已有信息直接回答，不要再调用工具。";

    /** 引擎内部的取消信号：onError 与检查点转译为统一出口。 */
    private static final class Cancelled extends RuntimeException {
    }

    private final ChatClient chatClient;

    private final ToolRegistry toolRegistry;

    private final ToolExecutor toolExecutor;

    private final ContextManager contextManager;

    private final ConfirmManager confirmManager;

    private final TraceRecorder trace;

    private final AgentProperties props;

    private final QaMessageMapper messageMapper;

    private final AgentRunMapper runMapper;

    private final AgentRunStepMapper stepMapper;

    public ReActEngine(ChatClient chatClient, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                       ContextManager contextManager, ConfirmManager confirmManager,
                       TraceRecorder trace, AgentProperties props,
                       QaMessageMapper messageMapper, AgentRunMapper runMapper,
                       AgentRunStepMapper stepMapper) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.confirmManager = confirmManager;
        this.trace = trace;
        this.props = props;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
    }

    /**
     * 跑一轮（或续跑一段）Agent 循环。messages 会被原位回填；终态事件与 complete 由本方法负责，
     * 调用方（AgentService）只负责 start 事件与 run 行的创建。
     */
    public void run(AgentRunContext ctx, AgentCancellation cancellation, SseEventSink sink) {
        long runId = ctx.runId();
        List<ChatMessage> messages = ctx.messages();
        long start = System.currentTimeMillis();
        StringBuilder generated = new StringBuilder();
        int[] promptTokens = {0};
        int[] completionTokens = {0};
        int iter = Math.max(1, ctx.startIteration());
        TraceContext traceCtx = new TraceContext(runId, new AtomicInteger());
        try {
            String lastFailedTool = null;
            int consecutiveFailures = 0;
            boolean forceFinalInjected = false;
            while (true) {
                checkCancelled(cancellation);
                if (iter > props.getMaxIterations() && !forceFinalInjected) {
                    messages.add(ChatMessage.user(FORCE_FINAL_HINT));
                    forceFinalInjected = true;
                }
                boolean forceFinal = forceFinalInjected;

                contextManager.enforce(messages);

                List<ToolCall> roundCalls = new ArrayList<>();
                StringBuilder roundContent = new StringBuilder();
                long roundStart = System.currentTimeMillis();
                List<ToolDefinition> tools = forceFinal ? List.of() : toolRegistry.definitions();

                trace.node(runId, traceCtx.seq(), "llm", "iter=" + iter, () -> {
                    Future<?> task = LLM_POOL.submit(() -> {
                        chatClient.stream(buildRequest(messages, tools), new StreamCallback() {
                            @Override
                            public void onContent(String delta) {
                                roundContent.append(delta);
                                generated.append(delta);
                                emitQuietly(sink, "content", new EvDelta(delta));
                            }

                            @Override
                            public void onThinking(String delta) {
                                emitQuietly(sink, "thinking", new EvDelta(delta));
                            }

                            @Override
                            public void onToolCalls(List<ToolCall> calls) {
                                roundCalls.addAll(calls);
                            }

                            @Override
                            public void onComplete(String finishReason, ChatUsage usage) {
                                if (usage != null) {
                                    promptTokens[0] += usage.promptTokens() == null ? 0 : usage.promptTokens();
                                    completionTokens[0] += usage.completionTokens() == null ? 0 : usage.completionTokens();
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                if (cancellation.isCancelled()) {
                                    throw new Cancelled();
                                }
                                throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
                            }
                        });
                        return null;
                    });
                    cancellation.bindLlmTask(task);
                    try {
                        task.get();
                    } catch (ExecutionException ee) {
                        throw asRuntime(ee.getCause());
                    } catch (CancellationException ce) {
                        // bindLlmTask 与任务内取消竞态：FutureTask 被 cancel 后 get() 抛本异常，视同取消信号
                        throw new Cancelled();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Cancelled();
                    }
                    return roundContent.length();
                });
                if (cancellation.isCancelled()) {
                    throw new Cancelled();
                }
                recordLlmStep(runId, iter, roundCalls, roundContent.toString(),
                        System.currentTimeMillis() - roundStart);

                if (!roundCalls.isEmpty()) {
                    // 消息回填红线：assistant(toolCalls) 原样入列（content=null）
                    messages.add(ChatMessage.assistantWithToolCalls(List.copyOf(roundCalls)));
                    for (ToolCall call : roundCalls) {
                        checkCancelled(cancellation);
                        emitQuietly(sink, "tool_call", new EvToolCall(call.name(), call.arguments()));

                        Tool tool = toolRegistry.find(call.name());
                        ToolResult result;
                        if (tool == null) {
                            result = ToolResult.fail("未知工具：" + call.name()
                                    + "。可用工具：" + toolRegistry.names() + "。请修正后重试或改用其他方式。",
                                    "unknown tool: " + call.name());
                        } else if (tool.requireConfirm()) {
                            // 写工具挂起（§8）：本轮 assistant(toolCalls) 已在 messages 快照中；
                            // 被挂起工具的结果消息不入列，续跑按 approve/reject 补写后继续循环
                            confirmManager.suspend(runId, messages, call, iter);
                            patchRun(runId, "WAITING_CONFIRM", iter,
                                    promptTokens[0], completionTokens[0],
                                    System.currentTimeMillis() - start, null);
                            sink.trySend("confirm_request",
                                    new EvConfirmRequest(runId, call.name(), call.arguments()));
                            sink.trySend("done", new EvDone(null,
                                    promptTokens[0], completionTokens[0],
                                    System.currentTimeMillis() - start));
                            sink.complete();
                            return;
                        } else {
                            result = toolExecutor.execute(tool, call, runId, traceCtx, iter);
                        }

                        // 结果按 toolCallId 配对紧随——顺序错一个供应商即 400
                        messages.add(ChatMessage.toolResult(call.id(), result.content()));
                        sink.trySend("tool_result", new EvToolResult(call.name(), result.success(),
                                truncate(result.content(), TOOL_RESULT_EVENT_MAX)));

                        if (result.success()) {
                            lastFailedTool = null;
                            consecutiveFailures = 0;
                        } else if (call.name().equals(lastFailedTool)) {
                            consecutiveFailures++;
                        } else {
                            lastFailedTool = call.name();
                            consecutiveFailures = 1;
                        }
                        if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                            messages.add(ChatMessage.user("工具 " + call.name() + " 已连续失败 "
                                    + consecutiveFailures + " 次，请改用其他方式完成任务，不要以相同参数重试。"));
                            lastFailedTool = null;
                            consecutiveFailures = 0;
                        }
                    }
                    iter++;
                    continue;
                }

                // stop/length：最终回答（content 与 tool_calls 协议互斥，roundContent 即答案）
                String answer = roundContent.toString();
                messages.add(ChatMessage.assistant(answer));
                long messageId = saveAnswerQuietly(ctx.sessionId(), answer,
                        promptTokens[0], completionTokens[0]);
                long costMs = System.currentTimeMillis() - start;
                finishRun(runId, "COMPLETED", iter, promptTokens[0], completionTokens[0], costMs, null);
                sink.trySend("done", new EvDone(messageId, promptTokens[0], completionTokens[0], costMs));
                sink.complete();
                return;
            }
        } catch (Cancelled c) {
            log.warn("Agent run 已取消 runId={}", runId);
            Long messageId = generated.length() > 0
                    ? saveAnswerQuietly(ctx.sessionId(), generated.toString(), promptTokens[0], completionTokens[0])
                    : null;
            long costMs = System.currentTimeMillis() - start;
            finishRun(runId, "CANCELLED", iter, promptTokens[0], completionTokens[0], costMs, null);
            sink.trySend("cancel", new EvCancel(messageId));
            sink.trySend("done", new EvDone(messageId, null, null, costMs));
            sink.complete();
        } catch (Exception e) {
            log.error("Agent run 失败 runId={}", runId, e);
            Long messageId = generated.length() > 0
                    ? saveAnswerQuietly(ctx.sessionId(), generated.toString(), promptTokens[0], completionTokens[0])
                    : null;
            long costMs = System.currentTimeMillis() - start;
            finishRun(runId, "FAILED", iter, promptTokens[0], completionTokens[0], costMs, e.getMessage());
            sink.trySend("error", new EvError(errorCodeOf(e), safeMessageOf(e)));
            sink.trySend("done", new EvDone(messageId, null, null, costMs));
            sink.complete();
        }
    }

    // ===== 内部构件 =====

    private ChatRequest buildRequest(List<ChatMessage> messages, List<ToolDefinition> tools) {
        return ChatRequest.builder()
                .messages(messages)
                .tools(tools)
                .temperature(0.0)
                .maxTokens(2048)
                .build();
    }

    private void checkCancelled(AgentCancellation cancellation) {
        if (cancellation.isCancelled()) {
            throw new Cancelled();
        }
    }

    private static RuntimeException asRuntime(Throwable t) {
        return t instanceof RuntimeException re ? re : new IllegalStateException(t);
    }

    private void recordLlmStep(long runId, int iter, List<ToolCall> calls, String content, long costMs) {
        try {
            AgentRunStep step = new AgentRunStep();
            step.setId(IdUtil.getSnowflakeNextId());
            step.setRunId(runId);
            step.setIter(iter);
            step.setType("LLM");
            step.setResult(calls.isEmpty()
                    ? truncate(content, STEP_TEXT_MAX)
                    : "tool_calls: " + calls.stream().map(ToolCall::name).toList());
            step.setCostMs(costMs);
            step.setStatus("SUCCESS");
            step.setCreatedAt(LocalDateTime.now());
            stepMapper.insert(step);
        } catch (Exception e) {
            log.warn("LLM step 落库失败 runId={}: {}", runId, e.getMessage());
        }
    }

    /** 最终/已生成回答落 qa_message（engine='agent'，与 QA 共享会话历史）。失败只留日志返回 0。 */
    private long saveAnswerQuietly(Long sessionId, String content, int promptTokens, int completionTokens) {
        try {
            QaMessage message = new QaMessage();
            message.setId(IdUtil.getSnowflakeNextId());
            message.setSessionId(sessionId);
            message.setRole("assistant");
            message.setContent(content);
            message.setEngine("agent");
            message.setPromptTokens(promptTokens);
            message.setCompletionTokens(completionTokens);
            message.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(message);
            return message.getId();
        } catch (Exception e) {
            log.error("agent 回答落库失败 contentLen={}", content.length(), e);
            return 0;
        }
    }

    private void finishRun(long runId, String status, int iterations,
                           int promptTokens, int completionTokens, long costMs, String errorMsg) {
        patchRun(runId, status, iterations, promptTokens, completionTokens, costMs, errorMsg);
    }

    private void patchRun(long runId, String status, int iterations,
                          int promptTokens, int completionTokens, long costMs, String errorMsg) {
        try {
            AgentRun patch = new AgentRun();
            patch.setId(runId);
            patch.setStatus(status);
            patch.setIterations(iterations);
            patch.setPromptTokens(promptTokens);
            patch.setCompletionTokens(completionTokens);
            patch.setCostMs(costMs);
            patch.setErrorMsg(errorMsg);
            patch.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("agent_run 状态落库失败 runId={} status={}: {}", runId, status, e.getMessage());
        }
    }

    private static void emitQuietly(SseEventSink sink, String event, Object data) {
        try {
            sink.send(event, data);
        } catch (Exception e) {
            log.debug("增量事件外发失败（客户端大概率已断开）: {}", e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private static String errorCodeOf(Exception e) {
        return e instanceof ModelClientException
                ? "C0001"
                : "B0001";
    }

    private static String safeMessageOf(Exception e) {
        return e instanceof ModelClientException m
                ? "远程模型调用失败（" + m.getType().getLabel() + "）"
                : "服务内部错误";
    }

    // ===== SSE 事件 data（复用协议既有事件类型，概设 §3.4 终态成对）=====

    record EvDelta(String delta) {
    }

    record EvToolCall(String toolName, String arguments) {
    }

    record EvToolResult(String toolName, boolean success, String content) {
    }

    record EvConfirmRequest(long runId, String toolName, String arguments) {
    }

    record EvCancel(Long messageId) {
    }

    record EvError(String code, String message) {
    }

    record EvDone(Long messageId, Integer promptTokens, Integer completionTokens, Long costMs) {
    }
}
