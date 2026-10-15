package com.zhixing.infra.keyword;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.dao.mapper.ChunkRow;
import com.zhixing.dao.mapper.RetrievalQueryMapper;
import com.zhixing.infra.vector.PgVectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pg_trgm 关键词检索：默认 trgm 模式（similarity + % 操作符）；
 * 整句相似召回过严时可用 zhixing.retrieve.keyword-mode=ilike 保底（详设 §4.1 注）。
 */
@Component
public class PgTrgmSearch implements KeywordSearch {

    private final RetrievalQueryMapper queryMapper;

    private final RetrieveProperties props;

    public PgTrgmSearch(RetrievalQueryMapper queryMapper, RetrieveProperties props) {
        this.queryMapper = queryMapper;
        this.props = props;
    }

    @Override
    public List<CitedChunk> topK(long kbId, String query, int topK) {
        List<ChunkRow> rows = "ilike".equalsIgnoreCase(props.getKeywordMode())
                ? queryMapper.ilikeTopK(kbId, query, topK)
                : queryMapper.trgmTopK(kbId, query, topK);
        return rows.stream().map(PgVectorStore::toCited).toList();
    }
}
