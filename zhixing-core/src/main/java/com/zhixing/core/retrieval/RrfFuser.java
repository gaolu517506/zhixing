package com.zhixing.core.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合，纯函数静态类（详设 §4.2，必须单元测试）。
 *
 * <p>RRF(d) = Σ_channel 1 / (k + rank_channel(d))，k 默认 60。
 * 只用排名不用原始分数——不同通道的分数量纲不可比（余弦相似 vs trgm 相似度），排名是唯一公共语言。
 * 同一 chunk 双路命中时分数取并集（求和），单路命中的文档自然被压后——这就是混合检索融合的本质。
 */
public final class RrfFuser {

    private static final int DEFAULT_K = 60;

    private RrfFuser() {
    }

    /**
     * 融合两路已排序（分数降序）结果，按 RRF 分数降序返回完整去重列表。
     * 任一通道为 null/空时自然退化为单通道排名。
     *
     * @param k RRF 常数；排位从 1 开始（rank=1 即榜首）
     */
    public static List<CitedChunk> fuse(List<CitedChunk> vector, List<CitedChunk> keyword, int k) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, CitedChunk> chunks = new HashMap<>();

        accumulate(vector, k, scores, chunks);
        accumulate(keyword, k, scores, chunks);

        List<CitedChunk> merged = new ArrayList<>(chunks.size());
        scores.forEach((chunkId, score) -> {
            CitedChunk c = chunks.get(chunkId);
            merged.add(new CitedChunk(c.chunkId(), c.docId(), c.docName(), c.content(), score));
        });
        merged.sort(Comparator.comparingDouble(CitedChunk::score).reversed());
        return merged;
    }

    public static List<CitedChunk> fuse(List<CitedChunk> vector, List<CitedChunk> keyword) {
        return fuse(vector, keyword, DEFAULT_K);
    }

    private static void accumulate(List<CitedChunk> ranked, int k,
                                   Map<Long, Double> scores, Map<Long, CitedChunk> chunks) {
        if (ranked == null) {
            return;
        }
        for (int i = 0; i < ranked.size(); i++) {
            CitedChunk c = ranked.get(i);
            scores.merge(c.chunkId(), 1.0 / (k + i + 1), Double::sum);
            chunks.putIfAbsent(c.chunkId(), c);
        }
    }
}
