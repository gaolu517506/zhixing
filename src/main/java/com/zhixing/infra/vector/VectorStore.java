package com.zhixing.infra.vector;

import com.zhixing.core.retrieval.CitedChunk;

import java.util.List;

/**
 * 向量检索通道接口。面向接口而非 pgvector 实现：换 Milvus/ES 时主链路零改动（概设 D1）。
 */
public interface VectorStore {

    /**
     * 余弦相似 top-K。queryVector 为查询文本的 embedding（维度必须与库内向量一致）。
     */
    List<CitedChunk> topK(long kbId, float[] queryVector, int topK);
}
