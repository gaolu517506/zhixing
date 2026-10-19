package com.zhixing.core.retrieval;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.config.TraceProperties;
import com.zhixing.infra.keyword.KeywordSearch;
import com.zhixing.infra.trace.TraceContext;
import com.zhixing.infra.trace.TraceRecorder;
import com.zhixing.infra.vector.VectorStore;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.RerankClient;
import com.zhixing.infra.llm.dto.ScoredDoc;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索引擎行为测试（任务书 §2.1 逐条验收）：双通道并行融合、任一通道异常按空结果降级且 trace 记 FAILED、
 * rerank 异常降级为 RRF 截断。全离线：store/client 全 mock，executor 用同线程直跑。
 */
class RetrievalEngineTest {

    private VectorStore vectorStore;

    private KeywordSearch keywordSearch;

    private EmbeddingClient embeddingClient;

    private RerankClient rerankClient;

    private TraceNodeMapper traceNodeMapper;

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
        traceNodeMapper = mock(TraceNodeMapper.class);
        TraceRecorder traceRecorder = new TraceRecorder(mock(TraceRunMapper.class), traceNodeMapper,
                new TraceProperties());
        when(embeddingClient.embed(anyString())).thenReturn(new float[1024]);
        // rerank 桩：原序直通（等价 Noop），score 用名次
        when(rerankClient.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(inv -> {
                    List<String> docs = inv.getArgument(1);
                    int topK = inv.getArgument(2);
                    return docs.stream().limit(topK)
                            .map(d -> new ScoredDoc(d, 1.0))
                            .toList();
                });
        engine = new RetrievalEngine(vectorStore, keywordSearch, embeddingClient, rerankClient,
                traceRecorder, new RetrieveProperties(), new com.zhixing.config.RewriteProperties(), Runnable::run);
    }

    private TraceContext traceOn() {
        return new TraceContext(1L, new AtomicInteger());
    }

    @Test
    void 双通道命中同chunk排前_rerank直通保序() {
        when(vectorStore.topK(anyLong(), any(), anyInt()))
                .thenReturn(List.of(chunk(1), chunk(2)));
        when(keywordSearch.topK(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(chunk(2), chunk(3)));

        RetrievalResult result = engine.retrieve(9L, "query", RetrieveBudget.of(2, 2, 5), traceOn());

        // c2 = 1/62(A) + 1/61(B) > c1 = 1/61(A) > c3 = 1/62(B)：RRF 数学已由 RrfFuserTest 锁定，
        // 这里验证引擎编排出的最终序；分数被 rerank 替换（直通桩全 1.0），不在此断言
        List<CitedChunk> fused = result.fused();
        assertThat(fused).extracting(CitedChunk::chunkId).containsExactly(2L, 1L, 3L);
        assertThat(result.vector()).hasSize(2);
        assertThat(result.keyword()).hasSize(2);
        // rerank 直通：最终序 = RRF 序，且 rerank 收到的是融合后的完整内容列表
        assertThat(fused).extracting(CitedChunk::content).containsExactly("内容2", "内容1", "内容3");
        org.mockito.Mockito.verify(rerankClient).rerank(org.mockito.ArgumentMatchers.eq("query"),
                org.mockito.ArgumentMatchers.argThat((List<String> docs) ->
                        docs.containsAll(List.of("内容1", "内容2", "内容3"))),
                org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    void 向量通道异常_按空结果降级不抛出且trace记FAILED() {
        when(vectorStore.topK(anyLong(), any(), anyInt())).thenThrow(new IllegalStateException("pg down"));
        when(keywordSearch.topK(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(chunk(7), chunk(8)));

        RetrievalResult result = engine.retrieve(9L, "query", RetrieveBudget.of(2, 2, 5), traceOn());

        // 不抛出，向量按空降级，融合退化为关键词单通道序
        assertThat(result.vector()).isEmpty();
        assertThat(result.fused()).extracting(CitedChunk::chunkId).containsExactly(7L, 8L);
        ArgumentCaptor<TraceNode> captor = ArgumentCaptor.forClass(TraceNode.class);
        verify(traceNodeMapper, times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(TraceNode::getName)
                .containsExactly("vector_search", "keyword_search", "fusion", "rerank");
        assertThat(captor.getAllValues().stream().filter(n -> "vector_search".equals(n.getName())))
                .allMatch(n -> "FAILED".equals(n.getStatus()));
        assertThat(captor.getAllValues().stream().filter(n -> !"vector_search".equals(n.getName())))
                .allMatch(n -> "SUCCESS".equals(n.getStatus()));
    }

    @Test
    void embedding失败等价向量通道降级() {
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding api down"));
        when(keywordSearch.topK(anyLong(), anyString(), anyInt())).thenReturn(List.of(chunk(5)));

        RetrievalResult result = engine.retrieve(9L, "query", RetrieveBudget.of(2, 2, 5), traceOn());

        assertThat(result.vector()).isEmpty();
        assertThat(result.fused()).extracting(CitedChunk::chunkId).containsExactly(5L);
    }

    @Test
    void rerank异常_降级为RRF序截断() {
        when(vectorStore.topK(anyLong(), any(), anyInt())).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));
        when(keywordSearch.topK(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        when(rerankClient.rerank(anyString(), anyList(), anyInt())).thenThrow(new IllegalStateException("rerank down"));

        RetrievalResult result = engine.retrieve(9L, "query", RetrieveBudget.of(3, 3, 2), traceOn());

        assertThat(result.fused()).extracting(CitedChunk::chunkId).containsExactly(1L, 2L);
    }

    @Test
    void traceCtx为null_零埋点但链路正常() {
        when(vectorStore.topK(anyLong(), any(), anyInt())).thenReturn(List.of(chunk(1)));
        when(keywordSearch.topK(anyLong(), anyString(), anyInt())).thenReturn(List.of(chunk(1)));

        RetrievalResult result = engine.retrieve(9L, "query", RetrieveBudget.of(2, 2, 5), null);

        assertThat(result.fused()).hasSize(1);
        verify(traceNodeMapper, times(0)).insert(any(TraceNode.class));
    }
}
