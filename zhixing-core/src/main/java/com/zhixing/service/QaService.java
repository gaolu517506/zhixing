package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.QaProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.config.RewriteProperties;
import com.zhixing.config.SummaryProperties;
import com.zhixing.core.memory.MemoryView;
import com.zhixing.core.memory.QueryRewriter;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.memory.SessionMemory;
import com.zhixing.core.memory.Summarizer;
import com.zhixing.core.memory.Turn;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
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
import com.zhixing.infra.sse.SseEmitterSink;
import com.zhixing.infra.sse.SseEventSink;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路 B 编排（详设-阶段1 §5.2 + 详设-阶段2 §7）：建会话 → start →
 * [记忆加载 → 摘要触发(降级不终态) → 问题重写(降级不终态)] → 检索(trace 四节点) →
 * Prompt 组装 → LLM 流式(trace llm_chat) → content 逐段转发 → 落库 → sources → done。
 *
 * <p>终态成对规则（概设 §3.4，协议级红线）：每条流恰好一个终态语义 + done 收尾——
 * 正常 = sources + done；异常 = 已生成内容先落库，再 error + done。
 * 客户端断开只停外发，服务端链路永远走完：要么完整落库，要么部分落库。
 *
 * <p>多轮编排（DL-14 ①②③）：zhixing.rewrite.enabled=true 时走 messages 数组组装
 * （system 规则 + 窗口历史 user/assistant 交替 + 末条 user 装资料块与独立问题），
 * 摘要/重写异常各自降级（摘要维持旧值、重写用原问题），绝不进入 failure 路径；
 * enabled=false 完整回到阶段 1 行为（单 prompt rag-v0 形态、无记忆、无重写、4 参检索）。
 */
@Slf4j
@Service
public class QaService {

    static final String ENGINE_WORKFLOW = "workflow";

    private static final int TITLE_MAX_LENGTH = 20;

    private static final int SNIPPET_MAX_LENGTH = 120;

    /** 摘要单次增量的最大轮数（详设 08-阶段2 §6：只取最早 4 轮做增量，防单次 prompt 过长）。 */
    private static final int SUMMARIZE_BATCH_TURNS = 4;

    private final ChatClient chatClient;

    private final RetrievalEngine retrievalEngine;

    private final QaSessionMapper sessionMapper;

    private final QaMessageMapper messageMapper;

    private final TraceRecorder trace;

    private final RetrieveProperties retrieveProps;

    private final QaProperties qaProps;

    private final ObjectMapper objectMapper;

    private final Executor qaPool;

    private final SessionMemory sessionMemory;

    private final Summarizer summarizer;

    private final QueryRewriter queryRewriter;

    private final SummaryProperties summaryProps;

    private final RewriteProperties rewriteProps;

    private final String promptTemplate;

    private final String systemTemplate;

    /** 长期记忆门面（R-T4 §2.3）：注入块 + 回合收尾异步仲裁；异常自吞，主链路无感知。 */
    private final com.zhixing.core.memory.LongTermMemoryService longTermMemory;

    public QaService(ChatClient chatClient,
                     RetrievalEngine retrievalEngine,
                     QaSessionMapper sessionMapper,
                     QaMessageMapper messageMapper,
                     TraceRecorder trace,
                     RetrieveProperties retrieveProps,
                     QaProperties qaProps,
                     ObjectMapper objectMapper,
                     @Qualifier("qa-pool") Executor qaPool,
                     SessionMemory sessionMemory,
                     Summarizer summarizer,
                     QueryRewriter queryRewriter,
                     SummaryProperties summaryProps,
                     RewriteProperties rewriteProps,
                     com.zhixing.core.memory.LongTermMemoryService longTermMemory) {
        this.chatClient = chatClient;
        this.retrievalEngine = retrievalEngine;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.trace = trace;
        this.retrieveProps = retrieveProps;
        this.qaProps = qaProps;
        this.objectMapper = objectMapper;
        this.qaPool = qaPool;
        this.sessionMemory = sessionMemory;
        this.summarizer = summarizer;
        this.queryRewriter = queryRewriter;
        this.summaryProps = summaryProps;
        this.rewriteProps = rewriteProps;
        this.longTermMemory = longTermMemory;
        this.promptTemplate = loadTemplate("prompts/rag-v0.md");
        this.systemTemplate = loadTemplate("prompts/rag-system-v1.md");
    }

    private static String loadTemplate(String path) {
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path + " 缺失", e);
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

            // ===== 多轮编排（详设-阶段2 §7；rewrite.enabled=false 完整回到阶段 1 行为） =====
            MemoryView memory = null;
            RewrittenQuery rewritten;
            if (rewriteProps.isEnabled()) {
                memory = sessionMemory.load(sessionId);
                memory = rollSummaryIfNeeded(sessionId, userMsgId, memory, runId, ctx);
                rewritten = rewriteIfNeeded(memory, req.getQuestion(), runId, ctx);
            } else {
                rewritten = new RewrittenQuery(req.getQuestion(), List.of(), false);
            }

            RetrieveBudget budget = RetrieveBudget.of(retrieveProps.getVectorTopK(), retrieveProps.getKeywordTopK(),
                    retrieveProps.getFinalTopK());
            RetrievalResult result = memory != null
                    ? retrievalEngine.retrieve(req.getKbId(), rewritten.standaloneQuestion(), rewritten.keywords(), budget, ctx)
                    : retrievalEngine.retrieve(req.getKbId(), req.getQuestion(), budget, ctx);
            sources = toSourceRefs(result.fused());

            // 长期记忆注入（R-T4 §2.3）：多轮分支 system 后追加 user 块；单 prompt 分支追加在模板前部
            String memoryBlock = longTermMemory.injectionBlock();
            List<ChatMessage> messages = memory != null
                    ? buildMultiTurnMessages(memory, rewritten.standaloneQuestion(), result.fused(), memoryBlock)
                    : List.of(ChatMessage.user(
                            memoryBlock == null ? buildPrompt(req.getQuestion(), result.fused())
                                    : memoryBlock + "\n\n" + buildPrompt(req.getQuestion(), result.fused())));

            ChatUsage[] usageHolder = new ChatUsage[1];
            trace.node(runId, ctx.seq(), "llm_chat", messages, () -> {
                chatClient.stream(buildChatRequest(messages), new StreamCallback() {
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

        // 回合收尾后：长期记忆异步仲裁（R-T4 §2.2；response 不等待，异常自吞）
        longTermMemory.arbitrateQuietlyAsync(sessionId, req.getQuestion(), content.toString());
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

    private ChatRequest buildChatRequest(List<ChatMessage> messages) {
        // DL-10 六字段；纯知识库问答零随机：temperature=0.0 + topP=1.0（详设 §5.1）
        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.0)
                .topP(1.0)
                .maxTokens(2048)
                .build();
    }

    // ===== 多轮编排（详设-阶段2 §6/§7，DL-14 ①②③）=====

    /**
     * 摘要触发（DL-14 ② 同步）：完成轮数（含窗外）达到阈值时，取窗口外最早 4 轮做增量滚动，
     * 落库 qa_session(summary_text, summarized_msg_id=本轮 user 消息 id) 并刷新本轮 memory。
     * 降级不终态（DL-14 ③）：roll 异常只告警并维持旧摘要（trace 已记 FAILED 节点），链路照走。
     * 节点边界对齐全库约定：trace 只包被点名的操作（roll），落库在节点外、失败不记为摘要失败。
     */
    private MemoryView rollSummaryIfNeeded(long sessionId, long userMsgId, MemoryView memory,
                                           long runId, TraceContext ctx) {
        try {
            if (sessionMemory.completedTurns(sessionId) < summaryProps.getTriggerTurns()) {
                return memory;
            }
            List<Turn> older = sessionMemory.turnsBeforeWindow(sessionId);
            if (older.isEmpty()) {
                return memory;
            }
            List<Turn> batch = older.subList(0, Math.min(SUMMARIZE_BATCH_TURNS * 2, older.size()));
            String rolled = trace.node(runId, ctx.seq(), "summarize", batch,
                    () -> summarizer.roll(memory.summary(), batch));
            saveSummaryQuietly(sessionId, userMsgId, rolled);
            return new MemoryView(rolled, memory.window());
        } catch (Exception e) {
            log.warn("摘要生成失败，维持旧值 sessionId={}: {}", sessionId, e.getMessage());
            return memory;
        }
    }

    /**
     * 摘要落库兜底（语义对齐 saveAssistantQuietly）：失败只留日志，本轮仍用新摘要——
     * 触发条件未变，下轮提问会重新滚动，不因一次写库抖动把旧摘要带回链路。
     */
    private void saveSummaryQuietly(long sessionId, long userMsgId, String summary) {
        try {
            QaSession patch = new QaSession();
            patch.setId(sessionId);
            patch.setSummaryText(summary);
            // 语义：摘要已覆盖到本轮 user 消息之前的全部轮次
            patch.setSummarizedMsgId(userMsgId);
            patch.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("摘要落库失败 sessionId={}（本轮仍用新摘要）: {}", sessionId, e.getMessage());
        }
    }

    /** 问题重写：有历史或摘要才调（首轮省一次 LLM 调用）；异常降级为原问题直通（DL-14 ③）。 */
    private RewrittenQuery rewriteIfNeeded(MemoryView memory, String question, long runId, TraceContext ctx) {
        if (memory.window().isEmpty() && memory.summary() == null) {
            return new RewrittenQuery(question, List.of(), false);
        }
        try {
            return trace.node(runId, ctx.seq(), "rewrite", question,
                    () -> queryRewriter.rewrite(memory.window(), memory.summary(), question));
        } catch (Exception e) {
            log.warn("问题重写失败，降级用原问题 sessionId traceRun={}：{}", runId, e.getMessage());
            return new RewrittenQuery(question, List.of(), false);
        }
    }

    /**
     * 多轮 messages 组装（DL-14 ①）：system 规则（rag-system-v1.md）在首位 + 窗口历史
     * user/assistant 原样交替 + 末条 user = 资料块 + 空行 + 独立问题（资料块格式沿用阶段 1）。
     */
    List<ChatMessage> buildMultiTurnMessages(MemoryView memory, String standaloneQuestion,
                                             List<CitedChunk> chunks, String memoryBlock) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemTemplate));
        if (memoryBlock != null) {
            // 长期记忆注入块紧跟 system（DL-17 ②：QA 与 Agent 双链路同源）
            messages.add(ChatMessage.user(memoryBlock));
        }
        for (Turn turn : memory.window()) {
            messages.add(new ChatMessage(turn.role(), turn.content()));
        }
        messages.add(ChatMessage.user(buildMaterial(chunks) + "\n\n" + standaloneQuestion));
        return List.copyOf(messages);
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
        return promptTemplate
                .replace("{chunks}", buildMaterial(chunks))
                .replace("{question}", question);
    }

    /** 资料块编号拼装（阶段 1 格式：[{i}] （来源：{docName}）\n{content}，空行分隔）。 */
    private static String buildMaterial(List<CitedChunk> chunks) {
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            CitedChunk c = chunks.get(i);
            material.append('[').append(i + 1).append("] （来源：").append(c.docName()).append("）\n")
                    .append(c.content());
            if (i < chunks.size() - 1) {
                material.append("\n\n");
            }
        }
        return material.toString();
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
