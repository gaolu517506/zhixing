package com.zhixing.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.AgentProperties;
import com.zhixing.dao.entity.AgentRunStep;
import com.zhixing.dao.mapper.AgentRunStepMapper;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行包装（详设 13-阶段3 §6）：参数解析 → 独立池执行（30s 超时隔离）→ trace 埋点
 * → agent_run_step 落库。任何失败都折算成 {@link ToolResult#fail}（模型自纠，不终态）。
 *
 * <p>独立 TOOL_POOL 的理由与检索池同构：引擎任务阻塞等待工具结果，若工具与本类调用方同池，
 * 池被运行任务占满时工具任务永远排队，超时永不相交，整池饿死。
 */
@Slf4j
@Component
public class ToolExecutor {

    private static final ExecutorService TOOL_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-tool-");
        t.setDaemon(true);
        return t;
    });

    private static final int STEP_TEXT_MAX = 2000;

    private final AgentRunStepMapper stepMapper;

    private final TraceRecorder trace;

    private final AgentProperties props;

    private final ObjectMapper objectMapper;

    public ToolExecutor(AgentRunStepMapper stepMapper, TraceRecorder trace,
                        AgentProperties props, ObjectMapper objectMapper) {
        this.stepMapper = stepMapper;
        this.trace = trace;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行单次工具调用并落库步骤流水。永不抛出：解析失败/超时/执行异常全部转
     * fail 结果（content 面向模型自纠，error 留日志与 step）。
     */
    public ToolResult execute(Tool tool, ToolCall call, long runId, TraceContext ctx, int iter) {
        long start = System.currentTimeMillis();
        ToolResult result = doExecute(tool, call, runId, ctx);
        saveStep(runId, iter, call, result, System.currentTimeMillis() - start);
        return result;
    }

    private ToolResult doExecute(Tool tool, ToolCall call, long runId, TraceContext ctx) {
        Map<String, Object> args;
        try {
            args = objectMapper.readValue(call.arguments() == null ? "{}" : call.arguments(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("工具参数解析失败 runId={} tool={}：{}", runId, call.name(), e.getMessage());
            return ToolResult.fail("工具参数不是合法 JSON，请对照工具 schema 修正后重试。",
                    "arguments parse failed: " + e.getMessage());
        }
        try {
            return trace.node(runId, ctx.seq(), "tool", call.name() + " " + call.arguments(),
                    () -> runWithTimeout(tool, args));
        } catch (RuntimeException e) {
            // trace.node 只在业务体抛出时上抛，业务体已全捕获——防御兜底
            log.warn("工具执行意外失败 runId={} tool={}", runId, call.name(), e);
            return ToolResult.fail("工具执行失败，请调整参数重试或改用其他方式。", String.valueOf(e));
        }
    }

    private ToolResult runWithTimeout(Tool tool, Map<String, Object> args) {
        Future<ToolResult> future = TOOL_POOL.submit(() -> tool.execute(args));
        try {
            return future.get(props.getToolTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.fail("工具执行超时，请调整参数重试或改用其他方式。",
                    "timeout after " + props.getToolTimeoutMs() + "ms");
        } catch (ExecutionException e) {
            log.warn("工具执行异常: {}", String.valueOf(e.getCause()));
            return ToolResult.fail("工具执行失败，请调整参数重试或改用其他方式。",
                    String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("工具执行被中断。", "interrupted");
        }
    }

    private void saveStep(long runId, int iter, ToolCall call, ToolResult result, long costMs) {
        try {
            AgentRunStep step = new AgentRunStep();
            step.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
            step.setRunId(runId);
            step.setIter(iter);
            step.setType("TOOL");
            step.setToolName(call.name());
            step.setArguments(truncate(call.arguments()));
            step.setResult(truncate(result.content()));
            step.setCostMs(costMs);
            step.setStatus(result.success() ? "SUCCESS" : "FAILED");
            step.setCreatedAt(LocalDateTime.now());
            stepMapper.insert(step);
        } catch (Exception e) {
            log.warn("agent_run_step 落库失败 runId={}: {}", runId, e.getMessage());
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= STEP_TEXT_MAX) {
            return text;
        }
        return text.substring(0, STEP_TEXT_MAX);
    }
}
