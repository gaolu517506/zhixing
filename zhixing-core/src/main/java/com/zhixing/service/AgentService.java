package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.AgentCancellation;
import com.zhixing.core.agent.AgentRunContext;
import com.zhixing.core.agent.ConfirmManager;
import com.zhixing.core.agent.PendingToolCall;
import com.zhixing.core.agent.ReActEngine;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolExecutor;
import com.zhixing.core.agent.ToolRegistry;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.sse.SseEmitterSink;
import com.zhixing.infra.sse.SseEventSink;
import com.zhixing.infra.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent SSE 编排（详设 13-阶段3 §10，与 QaService 同构）：run/confirm/cancel 三入口。
 *
 * <p>run：建/复用会话 → user 消息落 qa_message(engine='agent') → agent_run(RUNNING) 落库
 * → agent-pool 异步交 {@link ReActEngine}；confirm：从 agent_run 快照重建 messages →
 * approve 执行 pending（工具实现自校验对象存在性，失败以错误结果回填自纠）/ reject 回填
 * 拒绝文案 → 状态回 RUNNING → 新 SSE 续跑（iterations 延续）；cancel：置位
 * {@link AgentCancellation}（活动表查不到时兜底标记 RUNNING 残留行）。
 *
 * <p>事件协议零改动：start/thinking/content/tool_call/tool_result/confirm_request/cancel/error/done
 * 均为既有协议事件类型的使用；终态成对规则与 QA 同构（engine 内部兜底全部异常）。
 */
@Slf4j
@Service
public class AgentService {

    private static final String ENGINE_AGENT = "agent";

    private final ReActEngine engine;

    private final ConfirmManager confirmManager;

    private final ToolRegistry toolRegistry;

    private final ToolExecutor toolExecutor;

    private final AgentRunMapper runMapper;

    private final QaSessionMapper sessionMapper;

    private final QaMessageMapper messageMapper;

    private final AgentProperties props;

    private final Executor agentPool;

    /** 长期记忆门面（R-T4 §2.3）：system 尾部注入 + 回合收尾异步仲裁；异常自吞。 */
    private final com.zhixing.core.memory.LongTermMemoryService longTermMemory;

    /** Trace 埋点（v1.1）：AGENT 链路建 run（钉死与 agent_run 同 id）+ 回合终态回写。 */
    private final com.zhixing.infra.trace.TraceRecorder trace;

    /** 活动运行的取消注册表（runId → cancellation）；进程重启后残留行由 cancel 接口兜底标记。 */
    private final Map<Long, AgentCancellation> active = new ConcurrentHashMap<>();

    public AgentService(ReActEngine engine, ConfirmManager confirmManager, ToolRegistry toolRegistry,
                        ToolExecutor toolExecutor, AgentRunMapper runMapper,
                        QaSessionMapper sessionMapper, QaMessageMapper messageMapper,
                        AgentProperties props, @Qualifier("agent-pool") Executor agentPool,
                        com.zhixing.core.memory.LongTermMemoryService longTermMemory,
                        com.zhixing.infra.trace.TraceRecorder trace) {
        this.engine = engine;
        this.confirmManager = confirmManager;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.runMapper = runMapper;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.props = props;
        this.agentPool = agentPool;
        this.longTermMemory = longTermMemory;
        this.trace = trace;
    }

    // ===== 入口一：发起新运行 =====

    public SseEmitter run(AgentRunRequest req) {
        long sessionId = ensureSession(req.getSessionId(), req.getInput());
        long userMsgId = saveUserMessage(sessionId, req.getInput());

        long runId = IdUtil.getSnowflakeNextId();
        AgentRun agentRun = new AgentRun();
        agentRun.setId(runId);
        agentRun.setSessionId(sessionId);
        agentRun.setKbId(req.getKbId());
        agentRun.setInput(req.getInput());
        agentRun.setStatus("RUNNING");
        agentRun.setIterations(0);
        agentRun.setCreatedAt(LocalDateTime.now());
        agentRun.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(agentRun);
        // trace run 与 agent_run 同 id 建立（节点埋点以 agent runId 为键，缺行即孤儿——v1.1 修复）
        trace.startRun("AGENT", sessionId, runId);

        List<com.zhixing.infra.llm.dto.ChatMessage> messages = new java.util.ArrayList<>();
        // 长期记忆注入（R-T4 §2.3，DL-17 ②）：system 后追加独立 user 提示块（评审 P7：
        // 曾拼进 system 尾部，供应商对角色的区分失效；与 QA 侧 buildMultiTurnMessages 同序同源）
        messages.add(com.zhixing.infra.llm.dto.ChatMessage.system(SYSTEM_PROMPT));
        String memoryBlock = longTermMemory.injectionBlock();
        if (memoryBlock != null) {
            messages.add(com.zhixing.infra.llm.dto.ChatMessage.user(memoryBlock));
        }
        // 会话历史回放（v1.1）：此前 Agent 跨 run 完全失忆——「执行删除」这类续指令在新 run 里
        // 无从理解（2026-10-26 用户实测暴露）。回放近 8 轮（16 条，与 QA 窗口口径一致），仅取
        // user/assistant 文本；工具明细留在 agent_run_step/trace，不进上下文。当前输入在发起新
        // run 前已入库，排除之避免重复。
        List<QaMessage> history = new java.util.ArrayList<>(messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getSessionId, sessionId)
                .ne(QaMessage::getId, userMsgId)
                .orderByDesc(QaMessage::getId)
                .last("LIMIT 16")));
        java.util.Collections.reverse(history);
        for (QaMessage m : history) {
            if ("user".equals(m.getRole())) {
                messages.add(com.zhixing.infra.llm.dto.ChatMessage.user(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                messages.add(com.zhixing.infra.llm.dto.ChatMessage.assistant(m.getContent()));
            }
        }
        messages.add(com.zhixing.infra.llm.dto.ChatMessage.user(req.getInput()));
        return launch(runId, sessionId, req.getKbId(), messages, 0, userMsgId, "start", req.getInput());
    }

    // ===== 入口二：确认挂起恢复 =====

    public SseEmitter confirm(long runId, boolean approved) {
        AgentRun suspended = confirmManager.loadResumable(runId);
        // CAS 抢占先行（P0，全量评审报告 20261027）：写工具的单次执行保证——并发第二个 confirm
        // 在此被拒。若沿旧顺序「先执行后转状态」，读检查与写转换之间的窗口内写工具会双执行。
        // 代价：占用后执行若抛意外异常，run 已 RUNNING——故 approve 分支兜底转错误文案，续跑流不中断。
        if (runMapper.resumeFromConfirm(runId) == 0) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "该确认已被处理，请勿重复提交");
        }
        List<com.zhixing.infra.llm.dto.ChatMessage> messages =
                confirmManager.deserializeMessages(suspended.getMessagesSnapshot());
        PendingToolCall pending = confirmManager.deserializePendingTool(suspended.getPendingTool());
        int iterations = suspended.getIterations() == null ? 0 : suspended.getIterations();

        String resultContent;
        if (!approved) {
            resultContent = "用户拒绝了该操作，请调整方案或向用户说明。";
        } else {
            Tool tool = toolRegistry.find(pending.name());
            if (tool == null) {
                resultContent = "工具已不可用：" + pending.name() + "。请向用户说明情况。";
            } else {
                // approve：ToolExecutor 正常路径执行 pending；对象存在性由工具实现自校验
                //（挂起期间对象可能被删，工具返回 fail 即错误结果回填，模型自纠，不终态）
                ToolCall call = new ToolCall(pending.toolCallId(), pending.name(), pending.arguments());
                try {
                    resultContent = toolExecutor.execute(tool, call, runId,
                            new TraceContext(runId, new AtomicInteger()), iterations).content();
                } catch (Exception e) {
                    // 恢复名额已被 CAS 占用：异常也必须回填结果让续跑流完整（fail 文案哲学同上）
                    resultContent = "工具执行异常：" + e.getMessage() + "。请向用户说明情况。";
                }
            }
        }
        // 结果（或拒绝文案）按 toolCallId 配对补写（状态与 pending 清理已在 CAS 步完成）
        messages.add(com.zhixing.infra.llm.dto.ChatMessage.toolResult(pending.toolCallId(), resultContent));

        return launch(runId, suspended.getSessionId(), suspended.getKbId(), messages,
                iterations, null, "start", suspended.getInput());
    }

    // ===== 入口三：取消 =====

    public void cancel(long runId) {
        AgentCancellation cancellation = active.get(runId);
        if (cancellation != null) {
            cancellation.cancel();
            return;
        }
        // 活动表查不到：进程重启后的 RUNNING 残留行直接标记 CANCELLED
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "Agent run 不存在：" + runId);
        }
        if (!"RUNNING".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "当前状态不可取消：" + run.getStatus());
        }
        AgentRun patch = new AgentRun();
        patch.setId(runId);
        patch.setStatus("CANCELLED");
        patch.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(patch);
    }

    public List<AgentRun> listRuns(Long sessionId) {
        return runMapper.selectList(new LambdaQueryWrapper<AgentRun>()
                .eq(sessionId != null, AgentRun::getSessionId, sessionId)
                .orderByDesc(AgentRun::getId));
    }

    // ===== 公共：起流（run 与 confirm 共用）=====

    private SseEmitter launch(long runId, Long sessionId, Long kbId,
                              List<com.zhixing.infra.llm.dto.ChatMessage> messages,
                              int startIteration, Long messageIdForStart, String startEvent,
                              String userInputForMemory) {
        SseEmitter emitter = new SseEmitter(props.getEmitterTimeoutMs());
        SseEventSink sink = new SseEmitterSink(emitter);
        AgentCancellation cancellation = new AgentCancellation();
        active.put(runId, cancellation);
        // 断连/超时 → 取消链路（SseEmitterSink 内部回调只停外发，这里补业务取消）
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(t -> cancellation.cancel());

        AgentRunContext ctx = new AgentRunContext(runId, sessionId, kbId, messages, startIteration);
        CompletableFuture.runAsync(() -> {
            long startMs = System.currentTimeMillis();
            try {
                sink.send(startEvent, new EvStart(runId, messageIdForStart, ENGINE_AGENT));
                engine.run(ctx, cancellation, sink);
                // trace 终态回写：状态取 agent_run 实际落库值（COMPLETED/FAILED/SUSPENDED/CANCELLED 原样呈现）
                AgentRun done = runMapper.selectById(runId);
                trace.finishRun(runId, done == null ? "COMPLETED" : done.getStatus(),
                        System.currentTimeMillis() - startMs, done == null ? null : done.getErrorMsg());
                // 回合收尾后：长期记忆异步仲裁（R-T4 §2.2；取最后一条 assistant 作为本轮回答，
                // 挂起/取消等无回答场景由 blank 守卫跳过；response 不等待，异常自吞）
                longTermMemory.arbitrateQuietlyAsync(sessionId, userInputForMemory,
                        lastAssistantContent(messages));
            } catch (Exception e) {
                // engine 内部已兜底所有 Exception；到这里只剩 Error 级意外，补一次终态
                trace.finishRun(runId, "FAILED", System.currentTimeMillis() - startMs, e.getMessage());
                log.error("Agent 链路意外崩溃 runId={}", runId, e);
                sink.trySend("error", new EvError("B0001", "服务内部错误"));
                sink.trySend("done", new EvDone(null, null, null, null));
                sink.complete();
            } finally {
                active.remove(runId);
            }
        }, agentPool);
        return emitter;
    }

    /** messages 中最后一条 assistant 消息的 content（无则 null）。 */
    private static String lastAssistantContent(List<com.zhixing.infra.llm.dto.ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            com.zhixing.infra.llm.dto.ChatMessage m = messages.get(i);
            if ("assistant".equals(m.role())) {
                return m.content();
            }
        }
        return null;
    }

    // ===== 会话与消息（与 QA 共享会话；QaService 私有方法不可复用，按同语义实现）=====

    private long ensureSession(Long sessionId, String input) {
        if (sessionId != null) {
            QaSession existing = sessionMapper.selectById(sessionId);
            if (existing == null) {
                throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
            }
            return existing.getId();
        }
        QaSession session = new QaSession();
        session.setId(IdUtil.getSnowflakeNextId());
        session.setTitle(input.length() > 20 ? input.substring(0, 20) : input);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session.getId();
    }

    private long saveUserMessage(long sessionId, String input) {
        QaMessage message = new QaMessage();
        message.setId(IdUtil.getSnowflakeNextId());
        message.setSessionId(sessionId);
        message.setRole("user");
        message.setContent(input);
        message.setEngine(ENGINE_AGENT);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message.getId();
    }

    /** ToolResult → SSE 事件的窄化视图已并入 confirm 主流程。 */

    // ===== 请求/事件载体 =====

    record EvStart(long runId, Long messageId, String engine) {
    }

    record EvError(String code, String message) {
    }

    record EvDone(Long messageId, Integer promptTokens, Integer completionTokens, Long costMs) {
    }

    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    private static String loadSystemPrompt() {
        try {
            return new String(new org.springframework.core.io.ClassPathResource("prompts/agent-system-v1.md")
                    .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("prompts/agent-system-v1.md 缺失", e);
        }
    }
}
