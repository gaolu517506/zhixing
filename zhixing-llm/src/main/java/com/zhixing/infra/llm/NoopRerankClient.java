package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ScoredDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * Noop 重排：不做交叉编码，输入顺序（RRF 融合序）即最终序。
 * 评测数据证明 rerank 有收益时，才升级为真实实现——接口已留好，只需新增 provider。
 */
public class NoopRerankClient implements RerankClient {

    /**
     * 文档本身不带分数，score 按位置单调递减仅用于保持序关系，
     * 下游不得把它的绝对值当相关度用。
     */
    @Override
    public List<ScoredDoc> rerank(String query, List<String> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(Math.max(topK, 0), documents.size());
        List<ScoredDoc> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(new ScoredDoc(documents.get(i), documents.size() - i));
        }
        return result;
    }

    @Override
    public String provider() {
        return "noop";
    }
}
