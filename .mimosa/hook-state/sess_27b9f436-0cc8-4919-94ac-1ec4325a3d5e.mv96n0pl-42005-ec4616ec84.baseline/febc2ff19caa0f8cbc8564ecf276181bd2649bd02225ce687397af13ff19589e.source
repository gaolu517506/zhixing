package com.zhixing.infra.keyword;

import com.zhixing.core.retrieval.CitedChunk;

import java.util.List;

/**
 * 关键词检索通道接口。pg_trgm 是带退出策略的过渡实现（概设 D6）：
 * 召回质量若被评估集证明不足，按 zhparser → pg_search（PG 内真 BM25）→ ES 升级，主链路零改动。
 */
public interface KeywordSearch {

    List<CitedChunk> topK(long kbId, String query, int topK);
}
