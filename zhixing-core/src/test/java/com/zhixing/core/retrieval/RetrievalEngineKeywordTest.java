package com.zhixing.core.retrieval;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.config.RewriteProperties;
import com.zhixing.config.TraceProperties;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import com.zhixing.infra.keyword.KeywordSearch;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.RerankClient;
import com.zhixing.infra.llm.dto.ScoredDoc;
import com.zhixing.infra.trace.TraceRecorder;
import com.zhixing.infra.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 五参检索的关键词通道分支测试（T2-A 复核第 3 点对齐）：五参透传 keywords 的真实链路此前
 * 在 RetrievalEngineTest 无覆盖，本类补齐三分支——数组进通道 / 空数组退整句 / 开关关退整句。
 * 全离线，桩风格与 RetrievalEngineTest 一致。
 */
class RetrievalEngineKeywordTest {

    private VectorStore vectorStore;

    private KeywordSearch keywordSearch;

    private EmbeddingClient embeddingClient;

    private RerankClient rerankClient;

    private RewriteProperties rewriteProps;

    private RetrievalEngine engine;

    private static CitedChunk chunk(long id) {
        return new CitedChunk(id, id, "doc-" + id, "内容" + id, 0.9);
    }

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        keywordSearch = mock(KeywordSearch.class);
        embeddingClient = mock(EmbeddingClient.class);
        rerankClient = mock(RerankClient.class);
        rewriteProps = new RewriteProperties();
        when(embeddingClient.embed(anyString())).thenReturn(new float[1024]);
        when(rerankClient.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<String> docs = inv.getArgument(1);
            int topK = inv.getArgument(2);
            return docs.stream().limit(topK).map(d -> new ScoredDoc(d, 1.0)).toList();
        });
        engine = new RetrievalEngine(vectorStore, keywordSearch, embeddingClient, rerankClient,
                new TraceRecorder(mock(TraceRunMapper.class), mock(TraceNodeMapper.class), new TraceProperties()),
                new RetrieveProperties(), rewriteProps, Runnable::run);
    }

    @Test
    void keywords非空且开关开_数组进关键词通道() {
        when(vectorStore.topK(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(keywordSearch.topK(anyLong(), anyList(), anyInt())).thenReturn(List.of(chunk(11)));

        RetrievalResult result = engine.retrieve(9L, "原始问题", List.of("K80", "防水"),
                RetrieveBudget.of(2, 2, 5), null);

        verify(keywordSearch).topK(9L, List.of("K80", "防水"), 2);
        verify(keywordSearch, never()).topK(anyLong(), anyString(), anyInt());
        assertThat(result.keyword()).extracting(CitedChunk::chunkId).containsExactly(11L);
    }

    @Test
    void keywords为空_退整句匹配() {
        when(vectorStore.topK(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(keywordSearch.topK(anyLong(), anyString(), anyInt())).thenReturn(List.of(chunk(12)));

        RetrievalResult result = engine.retrieve(9L, "原始问题", List.of(),
                RetrieveBudget.of(2, 2, 5), null);

        verify(keywordSearch).topK(9L, "原始问题", 2);
        verify(keywordSearch, never()).topK(anyLong(), anyList(), anyInt());
        assertThat(result.keyword()).extracting(CitedChunk::chunkId).containsExactly(12L);
    }

    @Test
    void 开关关闭_关键词数组也退整句() {
        rewriteProps.setKeywordChannel(false);
        when(vectorStore.topK(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(keywordSearch.topK(anyLong(), anyString(), anyInt())).thenReturn(List.of(chunk(13)));

        engine.retrieve(9L, "原始问题", List.of("K80", "防水"), RetrieveBudget.of(2, 2, 5), null);

        verify(keywordSearch).topK(9L, "原始问题", 2);
        verify(keywordSearch, never()).topK(anyLong(), anyList(), anyInt());
    }
}
