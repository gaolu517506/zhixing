package com.zhixing.controller;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.Result;
import com.zhixing.config.EvalProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.memory.QueryRewriter;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.memory.Turn;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.util.List;

/**
 * 评测旁路（详设 §10.2，决策 D11）：直接调 RetrievalEngine 返回中间产物，
 * 两通道结果分开返回——这是"裁决关键词通道去留"（D6）的数据来源。
 * zhixing.eval.enabled=false（默认）时整个 Controller 不注册，接口真 404，生产零开销。
 * X-Eval-Key 防误用：评测是调优动作，不该被业务前端顺手调用。
 */
@Slf4j
@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixing.eval", name = "enabled", havingValue = "true")
public class EvalController {

    private final RetrievalEngine retrievalEngine;

    private final EvalProperties evalProps;

    private final RetrieveProperties retrieveProps;

    private final QueryRewriter queryRewriter;

    public EvalController(RetrievalEngine retrievalEngine, EvalProperties evalProps,
                          RetrieveProperties retrieveProps, QueryRewriter queryRewriter) {
        this.retrievalEngine = retrievalEngine;
        this.evalProps = evalProps;
        this.retrieveProps = retrieveProps;
        this.queryRewriter = queryRewriter;
    }

    /** history 非空时走真实重写路径（T2-6 多轮评估）：query 即最新问题，重写产物见返回体 rewritten。 */
    public record EvalRetrieveRequest(@NotNull Long kbId, @NotBlank String question, Integer topK,
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

    @PostMapping("/api/eval/retrieve")
    public Result<EvalRetrieveView> retrieve(@RequestHeader(value = "X-Eval-Key", required = false) String evalKey,
                                             @Valid @RequestBody EvalRetrieveRequest request) {
        checkKey(evalKey);
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

        return Result.ok(new EvalRetrieveView(
                detail.fused().stream().map(c -> c.docId()).distinct().toList(),
                detail.fused().stream().map(c -> c.chunkId()).toList(),
                new EvalRetrieveView.Channels(
                        detail.vector().stream().map(c -> c.chunkId()).toList(),
                        detail.keyword().stream().map(c -> c.chunkId()).toList()),
                costMs, rewritten));
    }

    private void checkKey(String evalKey) {
        if (evalKey == null || evalKey.isBlank()
                || !MessageDigest.isEqual(evalKey.getBytes(), evalProps.getKey().getBytes())) {
            throw new BusinessException(ErrorCode.EVAL_KEY_INVALID);
        }
    }
}
