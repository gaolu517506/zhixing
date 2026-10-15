package com.zhixing.core.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索质量三指标，纯静态函数（概设 D11）。入参：排序后的 docId 列表 + 期望 docId 集合。
 * 全部返回 [0,1]；空输入/全不命中一律 0（绝不抛异常——评测 runner 要对坏行容错）。
 */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /** Hit@K：前 K 名里是否含任一期望 doc（主指标，0/1）。 */
    public static double hitAtK(List<Long> rankedDocIds, Set<Long> expectedDocIds, int k) {
        if (rankedDocIds == null || expectedDocIds == null || expectedDocIds.isEmpty()) {
            return 0.0;
        }
        return topK(rankedDocIds, k).stream().anyMatch(expectedDocIds::contains) ? 1.0 : 0.0;
    }

    /** Recall@K：前 K 名命中的期望 doc 数 / 期望总数。 */
    public static double recallAtK(List<Long> rankedDocIds, Set<Long> expectedDocIds, int k) {
        if (rankedDocIds == null || expectedDocIds == null || expectedDocIds.isEmpty()) {
            return 0.0;
        }
        Set<Long> topK = new HashSet<>(topK(rankedDocIds, k));
        long hits = expectedDocIds.stream().filter(topK::contains).count();
        return (double) hits / expectedDocIds.size();
    }

    /** MRR@K：首个期望 doc 在前 K 名中排名的倒数；不在则 0。 */
    public static double mrrAtK(List<Long> rankedDocIds, Set<Long> expectedDocIds, int k) {
        if (rankedDocIds == null || expectedDocIds == null || expectedDocIds.isEmpty()) {
            return 0.0;
        }
        List<Long> topK = topK(rankedDocIds, k);
        for (int i = 0; i < topK.size(); i++) {
            if (expectedDocIds.contains(topK.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static List<Long> topK(List<Long> ranked, int k) {
        return ranked.size() <= k ? ranked : ranked.subList(0, k);
    }
}
