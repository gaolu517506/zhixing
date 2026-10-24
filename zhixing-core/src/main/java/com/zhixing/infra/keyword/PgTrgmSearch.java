package com.zhixing.infra.keyword;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.memory.LlmQueryRewriter;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.dao.mapper.ChunkRow;
import com.zhixing.dao.mapper.RetrievalQueryMapper;
import com.zhixing.infra.vector.PgVectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pg_trgm 关键词检索：默认 trgm 模式（similarity + % 操作符）；
 * 整句相似召回过严时可用 zhixing.retrieve.keyword-mode=ilike 保底（详设 §4.1 注）。
 * 阶段 2：覆盖数组 default——重写产出的关键词数组走 trgm OR 匹配（详设 08-阶段2 §8，
 * D6 退出策略第一级验证）；ilike 保底模式不复杂化，数组能力为 trgm 主路径专属。
 */
@Component
public class PgTrgmSearch implements KeywordSearch {

    /** 参数上限防御：与 {@link LlmQueryRewriter#KEYWORDS_MAX} 清洗双保险。 */
    private static final int KEYWORDS_MAX = 8;

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

    /**
     * 多关键词匹配：清洗（剔除空白/去重/上限 8）后走 trgm OR 数组查询。
     * 退化规则：keywords 空 → 空结果（重写降级时调用方应走整句路径，Engine 已分流）；
     * 单关键词或 ilike 保底模式 → 退化为首个关键词整句匹配（保底通道不复杂化）。
     */
    @Override
    public List<CitedChunk> topK(long kbId, List<String> keywords, int topK) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = keywords.stream()
                .filter(kw -> kw != null && !kw.isBlank())
                .map(String::trim)
                .distinct()
                .limit(KEYWORDS_MAX)
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        if (cleaned.size() == 1 || "ilike".equalsIgnoreCase(props.getKeywordMode())) {
            return topK(kbId, cleaned.get(0), topK);
        }
        return queryMapper.trgmKeywordsTopK(kbId, cleaned, topK).stream()
                .map(PgVectorStore::toCited)
                .toList();
    }
}
