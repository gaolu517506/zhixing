package com.zhixing.core.retrieval;

import java.util.List;

/**
 * 一次检索的完整产物：两通道原始结果 + 融合后最终结果。
 * QA 链路只消费 fused；评测旁路需要 channels 分开返回（D6 裁决关键词通道去留的数据源）。
 */
public record RetrievalResult(List<CitedChunk> vector, List<CitedChunk> keyword, List<CitedChunk> fused) {
}
