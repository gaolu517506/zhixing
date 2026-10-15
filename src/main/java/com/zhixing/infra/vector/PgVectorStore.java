package com.zhixing.infra.vector;

import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.dao.mapper.ChunkRow;
import com.zhixing.dao.mapper.RetrievalQueryMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * pgvector 实现：1 - (embedding &lt;=&gt; query::vector)，走 HNSW cosine 索引。
 * 查询向量以 pgvector 文本字面量 "[0.1,0.2,...]" 经 #{} 预编译绑定后 ::vector cast——
 * 参数是值不是拼进 SQL 的文本，无注入面（float[] → String 是纯数值拼接，非用户输入原样内插）。
 */
@Component
public class PgVectorStore implements VectorStore {

    private final RetrievalQueryMapper queryMapper;

    public PgVectorStore(RetrievalQueryMapper queryMapper) {
        this.queryMapper = queryMapper;
    }

    @Override
    public List<CitedChunk> topK(long kbId, float[] queryVector, int topK) {
        return queryMapper.vectorTopK(kbId, toPgVectorLiteral(queryVector), topK).stream()
                .map(PgVectorStore::toCited)
                .toList();
    }

    /** ChunkRow → CitedChunk；doc 缺失（LEFT JOIN 未命中）时 docName 回退为 doc-{id}。 */
    public static CitedChunk toCited(ChunkRow row) {
        String docName = row.getDocName() != null ? row.getDocName() : fallbackDocName(row.getDocId());
        return new CitedChunk(row.getChunkId(), row.getDocId(), docName, row.getContent(),
                row.getScore() != null ? row.getScore() : 0.0);
    }

    private static String fallbackDocName(Long docId) {
        return "doc-" + docId;
    }

    /** float[] → pgvector 文本格式（Math.usr 无关的 plain 数值，千分位等格式化绝不引入）。 */
    static String toPgVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float v : vector) {
            joiner.add(Float.toString(v));
        }
        return joiner.toString();
    }
}
