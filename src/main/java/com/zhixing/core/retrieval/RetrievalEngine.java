package com.zhixing.core.retrieval;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.config.RewriteProperties;
import com.zhixing.infra.keyword.KeywordSearch;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import com.zhixing.infra.vector.VectorStore;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.RerankClient;
import com.zhixing.infra.llm.dto.ScoredDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 检索编排（详设 §4.3）：双通道在 retrieval-pool 上并行，trace 记 vector/keyword/fusion/rerank 四节点。
 *
 * <p>降级语义（红线）：任一通道异常（含查询 embedding 调用失败）→ trace 记 FAILED 节点 +
 * 该通道按空结果降级，绝不向上抛——检索挂了问答不该挂，只是没有那一路的证据。
 *
 * <p>与 qa-pool 隔离的原因：QA 链路任务会阻塞在 LLM 流上长时间占线程，
 * 若通道任务与之同池，链路任务占满线程池时通道任务永远排队，join 永不返回，整池死锁。
 */
@Slf4j
@Component
public class RetrievalEngine {

    private final VectorStore vectorStore;

    private final KeywordSearch keywordSearch;

    private final EmbeddingClient embeddingClient;

    private final RerankClient rerankClient;

    private final TraceRecorder trace;

    private final RetrieveProperties props;

    private final RewriteProperties rewriteProps;

    private final Executor retrievalPool;

    public RetrievalEngine(VectorStore vectorStore,
                           KeywordSearch keywordSearch,
                           EmbeddingClient embeddingClient,
                           RerankClient rerankClient,
                           TraceRecorder trace,
                           RetrieveProperties props,
                           RewriteProperties rewriteProps,
                           @Qualifier("retrieval-pool") Executor retrievalPool) {
        this.vectorStore = vectorStore;
        this.keywordSearch = keywordSearch;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.trace = trace;
        this.props = props;
        this.rewriteProps = rewriteProps;
        this.retrievalPool = retrievalPool;
    }

    /**
     * 阶段 1 兼容签名：不带重写关键词，keyword 通道走整句。
     */
    public RetrievalResult retrieve(long kbId, String query, RetrieveBudget budget, TraceContext traceCtx) {
        return retrieve(kbId, query, null, budget, traceCtx);
    }

    /**
     * 双通道并行检索 + RRF 融合 + rerank 精排。traceCtx 传 null 则不埋点（评测旁路用）。
     * keywords 非空且 zhixing.rewrite.keyword-channel=true 时 keyword 通道走多关键词匹配（阶段 2），
     * 否则退回整句（重写降级 / 开关关闭 = 阶段 1 行为）。
     */
    public RetrievalResult retrieve(long kbId, String query, List<String> keywords,
                                    RetrieveBudget budget, TraceContext traceCtx) {
        boolean useKeywordArray = rewriteProps.isKeywordChannel() && keywords != null && !keywords.isEmpty();
        CompletableFuture<List<CitedChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> tracedChannel(traceCtx, "vector_search", query,
                        () -> vectorStore.topK(kbId, embeddingClient.embed(query), budget.vectorTopK())),
                retrievalPool);

        CompletableFuture<List<CitedChunk>> keywordFuture = CompletableFuture.supplyAsync(
                () -> tracedChannel(traceCtx, "keyword_search", query,
                        () -> useKeywordArray
                                ? keywordSearch.topK(kbId, keywords, budget.keywordTopK())
                                : keywordSearch.topK(kbId, query, budget.keywordTopK())),
                retrievalPool);

        List<CitedChunk> vector = vectorFuture.join();
        List<CitedChunk> keyword = keywordFuture.join();

        List<CitedChunk> fused = traced(traceCtx, "fusion", query,
                () -> RrfFuser.fuse(vector, keyword, props.getRrfK()));

        List<CitedChunk> finalTop = traced(traceCtx, "rerank", query, () -> rerank(fused, query, budget.rerankTopK()));
        return new RetrievalResult(vector, keyword, finalTop);
    }

    /**
     * rerank 精排：RerankClient 只认文本，按 content 映回 chunk（RRF 已去重，同 content 视为同块）。
     * rerank 异常时降级为 RRF 序截断——精排是锦上添花，不该让它打断问答。
     */
    private List<CitedChunk> rerank(List<CitedChunk> fused, String query, int topK) {
        if (fused.isEmpty()) {
            return List.of();
        }
        try {
            List<String> documents = fused.stream().map(CitedChunk::content).toList();
            Map<String, CitedChunk> byContent = new LinkedHashMap<>();
            fused.forEach(c -> byContent.putIfAbsent(c.content(), c));
            List<ScoredDoc> scored = rerankClient.rerank(query, documents, topK);
            List<CitedChunk> result = new ArrayList<>(scored.size());
            for (ScoredDoc sd : scored) {
                CitedChunk c = byContent.get(sd.document());
                if (c != null) {
                    result.add(new CitedChunk(c.chunkId(), c.docId(), c.docName(), c.content(), sd.score()));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("rerank 失败，降级为 RRF 序截断: {}", e.getMessage());
            return fused.subList(0, Math.min(topK, fused.size()));
        }
    }

    /** 单通道执行 + 埋点 + 降级：异常记 FAILED 节点后按空结果返回，不抛出。 */
    private List<CitedChunk> tracedChannel(TraceContext ctx, String name, String query,
                                           TraceRecorder.NodeBody<List<CitedChunk>> body) {
        if (ctx == null || ctx.runId() == 0) {
            try {
                return body.run();
            } catch (Exception e) {
                log.warn("[{}] 通道异常，按空结果降级: {}", name, e.getMessage());
                return List.of();
            }
        }
        try {
            return trace.node(ctx.runId(), ctx.seq(), name, query, body);
        } catch (Exception e) {
            log.warn("[{}] 通道异常，按空结果降级: {}", name, e.getMessage());
            return List.of();
        }
    }

    private <T> T traced(TraceContext ctx, String name, Object input, TraceRecorder.NodeBody<T> body) {
        if (ctx == null) {
            return body.run();
        }
        return trace.node(ctx.runId(), ctx.seq(), name, input, body);
    }
}
