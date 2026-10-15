package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ScoredDoc;

import java.util.List;

/**
 * 重排客户端。阶段 1 只有 {@link NoopRerankClient}（智谱暂无公开 rerank 端点）；
 * 日后接硅基流动/Jina 只需新增实现 + yaml 配置，本接口与调用方零改动。
 */
public interface RerankClient {

    String provider();

    List<ScoredDoc> rerank(String query, List<String> documents, int topK);
}
