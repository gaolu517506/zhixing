package com.zhixing.core.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.config.EvalProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.mapper.KbDocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 评测 runner（详设 §10.3，决策 D11 骨架交付）。
 * 真实评估集要等场景文档人工核对完成后才有；本类只交付"读集 → 逐条检索 → 指标汇总"的执行骨架，
 * 指标数学与集合解析在 EvalMetricsTest / EvalSetSupportTest 离线覆盖。
 * 花钱+写库：EVAL_RUN=true 环境变量显式放行，且 zhixing.eval.kb-id 必须配置为合法知识库。
 *
 * <p>待真实数据就绪时的两个既知缺口（合并后由 lead 拍板）：
 * ① expected_doc_ids 是业务码，需要"业务码 → kb_document.id"解析（当前按 file_name 去扩展名匹配）；
 * ② 评估集 jsonl 不带 kbId，取 zhixing.eval.kb-id 配置。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EVAL_RUN", matches = "true")
class EvalRunner {

    @Autowired
    private RetrievalEngine retrievalEngine;

    @Autowired
    private EvalProperties evalProps;

    @Autowired
    private RetrieveProperties retrieveProps;

    @Autowired
    private KbDocumentMapper docMapper;

    @Test
    void runEvaluation() {
        assumeTrue(evalProps.getKbId() > 0, "zhixing.eval.kb-id 未配置，跳过评测");
        List<EvalSetSupport.EvalCase> cases = EvalSetSupport.parse(Path.of(evalProps.getSetPath()));
        Map<Long, String> docIdToCode = loadDocCodes(evalProps.getKbId());

        RetrieveBudget budget = RetrieveBudget.of(retrieveProps.getVectorTopK(),
                retrieveProps.getKeywordTopK(), retrieveProps.getFinalTopK());
        List<EvalSetSupport.CaseResult> results = new ArrayList<>(cases.size());
        for (EvalSetSupport.EvalCase evalCase : cases) {
            RetrievalResult detail = retrievalEngine.retrieve(evalProps.getKbId(), evalCase.query(), budget, null);
            List<Long> rankedDocIds = detail.fused().stream().map(CitedChunk::docId).distinct().toList();
            Set<Long> expected = new HashSet<>(evalCase.expectedDocIds().stream()
                    .map(code -> resolveDocId(docIdToCode, code))
                    .toList());
            results.add(new EvalSetSupport.CaseResult(evalCase, rankedDocIds,
                    EvalMetrics.hitAtK(rankedDocIds, expected, 5),
                    EvalMetrics.recallAtK(rankedDocIds, expected, 5),
                    EvalMetrics.mrrAtK(rankedDocIds, expected, 10)));
        }

        printReport(results);
    }

    /** 业务码 = file_name 去扩展名（如 01-param-xiaomi-15-ultra）。双线合并后直接读 A 线 mapper。 */
    private Map<Long, String> loadDocCodes(long kbId) {
        Map<Long, String> codes = new LinkedHashMap<>();
        docMapper.selectList(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getKbId, kbId))
                .forEach(d -> codes.put(d.getId(), d.getFileName()));
        return codes;
    }

    private long resolveDocId(Map<Long, String> docIdToCode, String code) {
        return docIdToCode.entrySet().stream()
                .filter(e -> stripExtension(e.getValue()).equals(code))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(-1L); // 解析不到 → 视为不可命中，报表可见（Recall 分母仍计入）
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void printReport(List<EvalSetSupport.CaseResult> results) {
        System.out.println("=== 评测报告（Hit@5 / Recall@5 / MRR@10）===");
        System.out.println("difficulty  cases  Hit@5");
        EvalSetSupport.groupByDifficulty(results).forEach((difficulty, items) ->
                System.out.printf("%-10s  %-5d  %.3f%n", difficulty, items.size(),
                        EvalSetSupport.averageHitAt5(items)));
        double hit5 = results.stream().mapToDouble(EvalSetSupport.CaseResult::hitAt5).average().orElse(0);
        double recall5 = results.stream().mapToDouble(EvalSetSupport.CaseResult::recallAt5).average().orElse(0);
        double mrr10 = results.stream().mapToDouble(EvalSetSupport.CaseResult::mrrAt10).average().orElse(0);
        System.out.printf("overall     %-5d  %.3f（Recall@5=%.3f，MRR@10=%.3f）%n", results.size(), hit5, recall5, mrr10);
    }
}
