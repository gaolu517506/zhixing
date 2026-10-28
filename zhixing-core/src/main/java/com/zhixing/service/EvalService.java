package com.zhixing.service;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.memory.QueryRewriter;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.memory.Turn;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评测旁路编排（02 §B2 调用点清单中的「EvalService」）。原 EvalController 内联逻辑下沉：
 * 权限在 service 入口判定（controller 层零权限判断），评测虽然另有 X-Eval-Key 防误用门，
 * 读取知识库内容仍必须过库权限——两条门语义不同（防误用 vs 授权），缺一不可。
 */
@Slf4j
@Service
public class EvalService {

    private final RetrievalEngine retrievalEngine;

    private final RetrieveProperties retrieveProps;

    private final QueryRewriter queryRewriter;

    private final PermissionService permissionService;

    public EvalService(RetrievalEngine retrievalEngine, RetrieveProperties retrieveProps,
                       QueryRewriter queryRewriter, PermissionService permissionService) {
        this.retrievalEngine = retrievalEngine;
        this.retrieveProps = retrieveProps;
        this.queryRewriter = queryRewriter;
        this.permissionService = permissionService;
    }

    /** history 非空时走真实重写路径（T2-6 多轮评估）：query 即最新问题，重写产物见返回体 rewritten。 */
    public record EvalRetrieveRequest(Long kbId, String question, Integer topK,
                                      List<String> keywords, List<Turn> history) {
    }

    public record EvalRetrieveView(List<Long> docIds, List<Long> chunkIds,
                                   Channels channels, long costMs,
                                   Rewritten rewritten) {
        public record Channels(List<Long> vector, List<Long> keyword) {
        }

        /** history 非空时返回重写产物（观察重写质量）；单轮请求为 null。 */
        public record Rewritten(String standaloneQuestion, List<String> keywords) {
        }
    }

    public EvalRetrieveView retrieve(EvalRetrieveRequest request) {
        permissionService.requireAccess(request.kbId());
        long start = System.currentTimeMillis();
        // topK 显式给定时三通道同预算（便于单变量调参）；否则取全局配置
        RetrieveBudget budget = request.topK() != null
                ? RetrieveBudget.of(request.topK(), request.topK(), request.topK())
                : RetrieveBudget.of(retrieveProps.getVectorTopK(), retrieveProps.getKeywordTopK(),
                        retrieveProps.getFinalTopK());

        String effectiveQuery = request.question();
        List<String> effectiveKeywords = request.keywords();
        EvalRetrieveView.Rewritten rewritten = null;
        if (request.history() != null && !request.history().isEmpty()) {
            RewrittenQuery r = queryRewriter.rewrite(request.history(), null, request.question());
            effectiveQuery = r.standaloneQuestion();
            effectiveKeywords = r.keywords();
            rewritten = new EvalRetrieveView.Rewritten(r.standaloneQuestion(), r.keywords());
        }

        RetrievalResult detail = retrievalEngine.retrieve(request.kbId(), effectiveQuery,
                effectiveKeywords, budget, null);
        long costMs = System.currentTimeMillis() - start;
        log.info("eval retrieve: kbId={}, question={}, costMs={}", request.kbId(), request.question(), costMs);

        return new EvalRetrieveView(
                detail.fused().stream().map(c -> c.docId()).distinct().toList(),
                detail.fused().stream().map(c -> c.chunkId()).toList(),
                new EvalRetrieveView.Channels(
                        detail.vector().stream().map(c -> c.chunkId()).toList(),
                        detail.keyword().stream().map(c -> c.chunkId()).toList()),
                costMs, rewritten);
    }
}
