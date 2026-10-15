package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.QaProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import com.zhixing.controller.QaStreamRequest;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.ModelClientException;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路 B 编排（详设 §5.2）：建会话 → start → 检索(trace 四节点) → Prompt 组装 →
 * LLM 流式(trace llm_chat) → content 逐段转发 → 落库 → sources → done。
 *
 * <p>终态成对规则（概设 §3.4，协议级红线）：每条流恰好一个终态语义 + done 收尾——
 * 正常 = sources + done；异常 = 已生成内容先落库，再 error + done。
 * 客户端断开只停外发，服务端链路永远走完：要么完整落库，要么部分落库。
 *
 * <p>阶段 1 单轮无记忆：prompt 只放当前问题；qa_message 全量落库为阶段 2 留数据。
 * Prompt 组装用 resources/prompts/rag-v0.md（详设 §5.1 原文），资料块在代码里编号填充。
 */
@Slf4j
@Service
public class QaService {

    static final String ENGINE_WORKFLOW = "workflow";

    private static final int TITLE_MAX_LENGTH = 20;

    private static final int SNIPPET_MAX_LENGTH = 120;

    private final ChatClient chatClient;

    private final RetrievalEngine retrievalEngine;

    private final QaSessionMapper sessionMapper;

    private final QaMessageMapper messageMapper;

    private final TraceRecorder trace;

    private final RetrieveProperties retrieveProps;

    private final QaProperties qaProps;

    private final ObjectMapper objectMapper;

    private final Executor qaPool;

    private final String promptTemplate;

    public QaService(ChatClient chatClient,
                     RetrievalEngine retrievalEngine,
                     QaSessionMapper sessionMapper,
                     QaMessageMapper messageMapper,
                     TraceRecorder trace,
                     RetrieveProperties retrieveProps,
                     QaProperties qaProps,
                     ObjectMapper objectMapper,
                     @Qualifier("qa-pool") Executor qaPool) {
        this.chatClient = chatClient;
        this.retrievalEngine = retrievalEngine;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.trace = trace;
        this.retrieveProps = retrieveProps;
        this.qaProps = qaProps;
        this.objectMapper = objectMapper;
        this.qaPool = qaPool;
        this.promptTemplate = loadPromptTemplate();
    }

    private static String loadPromptTemplate() {
        try {
            return new String(new ClassPathResource("prompts/rag-v0.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompts/rag-v0.md 缺失", e);
        }
    }

    // ===== 入口：同步做廉价的会话准备（失败走全局异常翻译成 Result），链路本身异步 =====

    public SseEmitter streamQa(QaStreamRequest req) {
        long sessionId = ensureSession(req);
        long userMsgId = saveUserMessage(sessionId, req.getQuestion());
        SseEmitter emitter = new SseEmitter(qaProps.getEmitterTimeoutMs());
        SseEmitterSink sink = new SseEmitterSink(emitter);
        CompletableFuture.runAsync(() -> runQa(req, sessionId, userMsgId, sink), qaPool)
                .whenComplete((v, t) -> {
                    if (t != null) {
                        // runQa 内部已兜底所有 Exception；到这里只剩 Error/池拒绝等意外，补一次终态
                        log.error("QA 链路意外崩溃 sessionId={}", sessionId, t);
                        sink.trySend("error", new SseError(ErrorCode.SERVER_ERROR.code(), "服务内部错误"));
                        sink.trySend("done", new SseDone(null, null, null, null));
                        sink.complete();
                    }
                });
        return emitter;
    }

    // ===== 链路编排（包可见：QaServiceTest 直调并断言事件序）=====

    void runQa(QaStreamRequest req, long sessionId, long userMsgId, SseEventSink sink) {
        long runId = trace.startRun("QA", sessionId);
        TraceContext ctx = new TraceContext(runId, new AtomicInteger());
        long start = System.currentTimeMillis();

        // content 在 try 外声明：流中途失败时已生成的部分内容仍可落库（终态成对规则）
        StringBuilder content = new StringBuilder();
        List<SourceRef> sources = null;
        ChatUsage usage = null;
        Exception failure = null;
        try {
            sink.send("start", new SseStart(runId, userMsgId, ENGINE_WORKFLOW));

            RetrievalResult result = retrievalEngine.retrieve(req.getKbId(), req.getQuestion(),
                    RetrieveBudget.of(retrieveProps.getVectorTopK(), retrieveProps.getKeywordTopK(),
                            retrieveProps.getFinalTopK()),
                    ctx);
            sources = toSourceRefs(result.fused());

            String prompt = buildPrompt(req.getQuestion(), result.fused());
            ChatUsage[] usageHolder = new ChatUsage[1];
            trace.node(runId, ctx.seq(), "llm_chat", prompt, () -> {
                chatClient.stream(buildChatRequest(prompt), new StreamCallback() {
                    @Override
                    public void onContent(String delta) {
                        content.append(delta);
                        emitQuietly(sink, "content", new SseDelta(delta));
                    }

                    @Override
                    public void onThinking(String delta) {
                        emitQuietly(sink, "thinking", new SseDelta(delta));
                    }

                    @Override
                    public void onComplete(String finishReason, ChatUsage u) {
                        usageHolder[0] = u;
                    }

                    @Override
                    public void onError(Throwable t) {
                        // D5 契约：stream() 的失败走回调不抛出；这里转抛——llm_chat 节点记 FAILED，外层走 error+done
                        throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
                    }
                });
                return content.length();
            });
            usage = usageHolder[0];
        } catch (Exception e) {
            failure = e;
        }

        finish(sessionId, sink, runId, start, content, sources, usage, failure);
    }

    /**
     * 收尾：终态成对规则的唯一落点。正常 = sources + done；异常 = 已生成内容先落库，再 error + done。
     * 本方法自身必须走到 complete()：持久化再失败也不许击穿终态成对（收尾落库异常降级为
     * 纯 error+done，完整答案只留服务端日志）——实测教训：sources 列 jsonb 写入失败曾让
     * finally 的 complete() 抢在 error/done 之前关闭连接。
     */
    private void finish(long sessionId, SseEventSink sink, long runId, long start,
                        StringBuilder content, List<SourceRef> sources, ChatUsage usage, Exception failure) {
        long costMs = System.currentTimeMillis() - start;
        boolean hasArtifacts = content.length() > 0 || sources != null;
        try {
            if (failure == null) {
                long messageId = saveAssistantQuietly(sessionId, content.toString(), sources, usage, costMs);
                trace.finishRun(runId, "COMPLETED", costMs, null);
                sink.trySend("sources", sources == null ? List.of() : sources);
                // messageId=0 表示落库失败：回答已完整送达但未持久化，协议上以 null 告知前端
                sink.trySend("done", new SseDone(messageId > 0 ? messageId : null,
                        usage != null ? usage.promptTokens() : null,
                        usage != null ? usage.completionTokens() : null,
                        costMs));
                return;
            }
            // 异常路径：先落库（已生成的部分内容必须留下），再发终态
            log.error("QA 链路失败 sessionId={}", sessionId, failure);
            Long messageId = null;
            if (hasArtifacts) {
                messageId = saveAssistantQuietly(sessionId, content.toString(), sources, usage, costMs);
            }
            trace.finishRun(runId, "FAILED", costMs, failure.getMessage());
            sink.trySend("error", new SseError(errorCodeOf(failure), safeMessageOf(failure)));
            sink.trySend("done", new SseDone(messageId, null, null, costMs));
        } catch (Exception finishFailure) {
            // 收尾自身失败（如 finishRun 异常）：终态成对绝不能缺 done
            log.error("QA 收尾流程异常 sessionId={}", sessionId, finishFailure);
            sink.trySend("error", new SseError(ErrorCode.SERVER_ERROR.code(), "服务内部错误"));
            sink.trySend("done", new SseDone(null, null, null, costMs));
        } finally {
            sink.complete();
        }
    }

    /** 收尾落库兜底：失败只留日志，返回 0（消息没存上），绝不让它打断终态事件序列。 */
    private long saveAssistantQuietly(long sessionId, String content, List<SourceRef> sources,
                                      ChatUsage usage, long costMs) {
        try {
            return saveAssistantMessage(sessionId, content, sources, usage, costMs);
        } catch (Exception e) {
            log.error("assistant 消息落库失败 sessionId={} contentLen={}", sessionId, content.length(), e);
            return 0;
        }
    }

    /**
     * 流中事件外发：客户端断开时 send 失败只停外发，LLM 流继续走完并落库（终态成对规则）。
     * start/sources/done 走 sink.send/trySend（在编排层处理异常），增量转发在这里吞 IO 异常。
     */
    private static void emitQuietly(SseEventSink sink, String event, Object data) {
        try {
            sink.send(event, data);
        } catch (IOException e) {
            log.debug("增量事件外发失败（客户端大概率已断开）: {}", e.getMessage());
        }
    }

    private static String errorCodeOf(Exception e) {
        return e instanceof ModelClientException
                ? ErrorCode.REMOTE_MODEL_ERROR.code()
                : ErrorCode.SERVER_ERROR.code();
    }

    private static String safeMessageOf(Exception e) {
        return e instanceof ModelClientException m
                ? "远程模型调用失败（" + m.getType().getLabel() + "）"
                : "服务内部错误";
    }

    private ChatRequest buildChatRequest(String prompt) {
        // DL-10 六字段；纯知识库问答零随机：temperature=0.0 + topP=1.0（详设 §5.1）
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.0)
                .topP(1.0)
                .maxTokens(2048)
                .build();
    }

    // ===== 会话与消息 =====

    private long ensureSession(QaStreamRequest req) {
        if (req.getSessionId() != null) {
            QaSession existing = sessionMapper.selectById(req.getSessionId());
            if (existing == null) {
                throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
            }
            return existing.getId();
        }
        QaSession session = new QaSession();
        session.setId(IdUtil.getSnowflakeNextId());
        session.setTitle(truncate(req.getQuestion(), TITLE_MAX_LENGTH));
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session.getId();
    }

    private long saveUserMessage(long sessionId, String question) {
        QaMessage message = new QaMessage();
        message.setId(IdUtil.getSnowflakeNextId());
        message.setSessionId(sessionId);
        message.setRole("user");
        message.setContent(question);
        message.setEngine(ENGINE_WORKFLOW);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message.getId();
    }

    private long saveAssistantMessage(long sessionId, String content, List<SourceRef> sources,
                                      ChatUsage usage, long costMs) {
        QaMessage message = new QaMessage();
        message.setId(IdUtil.getSnowflakeNextId());
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent(content);
        message.setEngine(ENGINE_WORKFLOW);
        message.setSources(toSourcesJson(sources));
        message.setPromptTokens(usage != null ? usage.promptTokens() : null);
        message.setCompletionTokens(usage != null ? usage.completionTokens() : null);
        message.setCostMs(costMs);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message.getId();
    }

    private String toSourcesJson(List<SourceRef> sources) {
        if (sources == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("sources 序列化失败，按 null 落库: {}", e.getMessage());
            return null;
        }
    }

    // ===== 组装 =====

    List<SourceRef> toSourceRefs(List<CitedChunk> chunks) {
        return chunks.stream()
                .map(c -> new SourceRef(c.chunkId(), c.docName(), c.score(),
                        truncate(c.content(), SNIPPET_MAX_LENGTH)))
                .toList();
    }

    String buildPrompt(String question, List<CitedChunk> chunks) {
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            CitedChunk c = chunks.get(i);
            material.append('[').append(i + 1).append("] （来源：").append(c.docName()).append("）\n")
                    .append(c.content());
            if (i < chunks.size() - 1) {
                material.append("\n\n");
            }
        }
        return promptTemplate
                .replace("{chunks}", material.toString())
                .replace("{question}", question);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    // ===== 查询 =====

    public List<QaSession> listSessions() {
        return sessionMapper.selectList(new LambdaQueryWrapper<QaSession>()
                .orderByDesc(QaSession::getId)
                .last("LIMIT 50"));
    }

    public List<QaMessage> listMessages(long sessionId) {
        QaSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getSessionId, sessionId)
                .orderByAsc(QaMessage::getId));
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    // ===== SSE 事件 data 结构（概设 §3.4 协议）=====

    record SseStart(long runId, long messageId, String engine) {
    }

    record SseDelta(String delta) {
    }

    record SseError(String code, String message) {
    }

    record SseDone(Long messageId, Integer promptTokens, Integer completionTokens, Long costMs) {
    }
}
