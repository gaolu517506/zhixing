package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.eval.EvalMetrics;
import com.zhixing.core.eval.EvalSetSupport;
import com.zhixing.core.memory.QueryRewriter;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.memory.Turn;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import com.zhixing.dao.entity.EvalRun;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.mapper.EvalRunMapper;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评估运行执行段（eval-pool 异步，02-技术方案 §B6）：逐题「解析 →（多轮先重写）→ 检索 →
 * 业务码解析 → 三指标」→ 指标汇总落 report（含 delta）→ COMPLETED；执行失败落 FAILED +
 * error_msg + EVAL_RUN FAIL 审计。与门控测试类 EvalRunner 逐行对齐，内核零改动：
 * 业务码=file_name 去扩展名；解析不到记 -1（视为不可命中，Recall 分母仍计入）；
 * 数学在 {@link EvalMetrics}、解析/分层在 {@link EvalSetSupport} 原样复用。
 */
@Slf4j
@Component
public class EvalRunExecutor {

    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final String STATUS_FAILED = "FAILED";

    private static final int ERROR_MSG_MAX = 500;

    private final RetrievalEngine retrievalEngine;

    private final QueryRewriter queryRewriter;

    private final EvalRunMapper runMapper;

    private final KbDocumentMapper docMapper;

    private final KbChunkMapper chunkMapper;

    private final RetrieveProperties retrieveProps;

    private final AuditRecorder audit;

    private final ObjectMapper objectMapper;

    public EvalRunExecutor(RetrievalEngine retrievalEngine, QueryRewriter queryRewriter,
                           EvalRunMapper runMapper, KbDocumentMapper docMapper, KbChunkMapper chunkMapper,
                           RetrieveProperties retrieveProps, AuditRecorder audit, ObjectMapper objectMapper) {
        this.retrievalEngine = retrievalEngine;
        this.queryRewriter = queryRewriter;
        this.runMapper = runMapper;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.retrieveProps = retrieveProps;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** eval-pool 提交的唯一入口：异常全部自兜——线程池里不允许未捕获异常静默吞掉终态落库。 */
    public void execute(long runId, long kbId, List<EvalSetSupport.EvalCase> cases) {
        try {
            Map<Long, String> docIdToCode = loadDocCodes(kbId);
            RetrieveBudget budget = RetrieveBudget.of(retrieveProps.getVectorTopK(),
                    retrieveProps.getKeywordTopK(), retrieveProps.getFinalTopK());
            List<EvalSetSupport.CaseResult> results = new ArrayList<>(cases.size());
            for (EvalSetSupport.EvalCase evalCase : cases) {
                // 多轮用例（history 非空）走真实重写路径（T2-6）；单轮用例保持 Baseline-1 行为
                List<Turn> history = evalCase.history() == null ? List.of() : evalCase.history();
                String query = evalCase.query();
                List<String> keywords = null;
                if (!history.isEmpty()) {
                    RewrittenQuery rewritten = queryRewriter.rewrite(history, null, query);
                    query = rewritten.standaloneQuestion();
                    keywords = rewritten.keywords();
                }
                RetrievalResult detail = retrievalEngine.retrieve(kbId, query, keywords, budget, null);
                List<Long> rankedDocIds = detail.fused().stream().map(CitedChunk::docId).distinct().toList();
                Set<Long> expected = new HashSet<>(evalCase.expectedDocIds().stream()
                        .map(code -> resolveDocId(docIdToCode, code))
                        .toList());
                results.add(new EvalSetSupport.CaseResult(evalCase, rankedDocIds,
                        EvalMetrics.hitAtK(rankedDocIds, expected, 5),
                        EvalMetrics.recallAtK(rankedDocIds, expected, 5),
                        EvalMetrics.mrrAtK(rankedDocIds, expected, 10)));
            }

            String report = buildReport(kbId, runId, results);
            EvalRun patch = new EvalRun();
            patch.setId(runId);
            patch.setStatus(STATUS_COMPLETED);
            patch.setReport(report);
            patch.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(patch);
            log.info("评估运行完成 runId={} kbId={} cases={}", runId, kbId, cases.size());
        } catch (Exception e) {
            log.error("评估运行失败 runId={} kbId={}", runId, kbId, e);
            EvalRun patch = new EvalRun();
            patch.setId(runId);
            patch.setStatus(STATUS_FAILED);
            String errorMsg = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            patch.setErrorMsg(errorMsg);
            patch.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(patch);
            // 失败落因审计（发起事件已记 OK——两次事件分别是「发起」与「落因」）
            String detailJson = failDetailJson(kbId, errorMsg);
            audit.record(null, null, AuditAction.EVAL_RUN.name(),
                    "EVAL", String.valueOf(runId), AuditResult.FAIL, detailJson);
        }
    }

    /**
     * 报告组装：overall 三指标 + easy/medium/hard 分层 + 语料规模快照 + delta
     * （同库上一个 COMPLETED 运行对比；无基线为 null——「回归基线」定性由前端按 delta 呈现）。
     */
    private String buildReport(long kbId, long runId, List<EvalSetSupport.CaseResult> results) {
        double hitAt5 = results.stream().mapToDouble(EvalSetSupport.CaseResult::hitAt5).average().orElse(0);
        double recallAt5 = results.stream().mapToDouble(EvalSetSupport.CaseResult::recallAt5).average().orElse(0);
        double mrrAt10 = results.stream().mapToDouble(EvalSetSupport.CaseResult::mrrAt10).average().orElse(0);

        Map<String, Object> layers = new LinkedHashMap<>();
        EvalSetSupport.groupByDifficulty(results).forEach((difficulty, items) -> {
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put("cases", items.size());
            layer.put("hitAt5", round(EvalSetSupport.averageHitAt5(items)));
            layer.put("recallAt5", round(items.stream()
                    .mapToDouble(EvalSetSupport.CaseResult::recallAt5).average().orElse(0)));
            layer.put("mrrAt10", round(items.stream()
                    .mapToDouble(EvalSetSupport.CaseResult::mrrAt10).average().orElse(0)));
            layers.put(difficulty, layer);
        });

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("hitAt5", round(hitAt5));
        report.put("recallAt5", round(recallAt5));
        report.put("mrrAt10", round(mrrAt10));
        report.put("cases", results.size());
        report.put("layers", layers);
        report.put("corpus", corpusOf(kbId));

        // 上一 COMPLETED 运行（wrapper 排序 + 取首条）
        List<EvalRun> previousRuns = runMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getKbId, kbId)
                .eq(EvalRun::getStatus, STATUS_COMPLETED)
                .ne(EvalRun::getId, runId)
                .isNotNull(EvalRun::getReport)
                .orderByDesc(EvalRun::getFinishedAt)
                .orderByDesc(EvalRun::getId));
        if (!previousRuns.isEmpty()) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("vsLastRunId", previousRuns.get(0).getId());
            delta.put("hitAt5", round(hitAt5 - metricOf(previousRuns.get(0), "hitAt5")));
            delta.put("recallAt5", round(recallAt5 - metricOf(previousRuns.get(0), "recallAt5")));
            delta.put("mrrAt10", round(mrrAt10 - metricOf(previousRuns.get(0), "mrrAt10")));
            report.put("delta", delta);
        } else {
            report.put("delta", null);
        }
        return writeJson(report);
    }

    /** 语料规模快照（完成时点）：诚实标注回归结论的语料基数。 */
    private Map<String, Object> corpusOf(long kbId) {
        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("docs", docMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId)));
        corpus.put("chunks", chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, kbId)));
        return corpus;
    }

    // ===== 内部（业务码解析/指标对齐 EvalRunner 测试类） =====

    /** 业务码 = file_name 去扩展名（EvalRunner 原语义）。 */
    private Map<Long, String> loadDocCodes(long kbId) {
        Map<Long, String> codes = new LinkedHashMap<>();
        docMapper.selectList(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getKbId, kbId))
                .forEach(d -> codes.put(d.getId(), d.getFileName()));
        return codes;
    }

    /** 解析不到 → -1（视为不可命中，Recall 分母仍计入——EvalRunner 原语义）。 */
    private long resolveDocId(Map<Long, String> docIdToCode, String code) {
        return docIdToCode.entrySet().stream()
                .filter(e -> stripExtension(e.getValue()).equals(code))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(-1L);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private double metricOf(EvalRun previous, String metric) {
        try {
            JsonNode node = objectMapper.readTree(previous.getReport());
            return node.path(metric).asDouble(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评估报告序列化失败", e);
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static String truncate(String text) {
        return text.length() <= ERROR_MSG_MAX ? text : text.substring(0, ERROR_MSG_MAX);
    }

    private String failDetailJson(long kbId, String errorMsg) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kbId", kbId);
            map.put("error", errorMsg);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
