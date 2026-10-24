package com.zhixing.infra.keyword;

import com.zhixing.core.retrieval.CitedChunk;

import java.util.List;

/**
 * 关键词检索通道接口。pg_trgm 是带退出策略的过渡实现（概设 D6）：
 * 召回质量若被评估集证明不足，按 zhparser → pg_search（PG 内真 BM25）→ ES 升级，主链路零改动。
 */
public interface KeywordSearch {

    List<CitedChunk> topK(long kbId, String query, int topK);

    /**
     * 多关键词匹配（阶段 2 契约基线，详设 08-阶段2 §8；D6 退出策略第一级验证）。
     * 默认实现临时退化为「取首个关键词的整句匹配」——T2-B 线以 trgm 数组 OR SQL 覆盖。
     */
    default List<CitedChunk> topK(long kbId, List<String> keywords, int topK) {
        return keywords == null || keywords.isEmpty()
                ? List.of()
                : topK(kbId, keywords.get(0), topK);
    }
}
