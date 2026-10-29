package com.zhixing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.eval.EvalSetSupport;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.dao.entity.EvalRun;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.mapper.EvalRunMapper;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估执行段单测（02 §B6 测试点：runner 复用不改内核、FAILED 落因、delta 正确性）。
 * retrieval/rewriter 用桩对齐 EvalRunner 语义：业务码=file_name 去扩展名、未命中记 -1。
 */
class EvalRunExecutorTest {

    private RetrievalEngine retrievalEngine;

    private EvalRunMapper runMapper;

    private KbDocumentMapper docMapper;

    private KbChunkMapper chunkMapper;

    private AuditRecorder audit;

    private EvalRunExecutor executor;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        retrievalEngine = mock(RetrievalEngine.class);
        runMapper = mock(EvalRunMapper.class);
        docMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        audit = mock(AuditRecorder.class);
        // 真实 QueryRewriter 走 NoopQueryRewriter 不可注入——多轮用例在执行器里构造，
        // 这里用桩：单轮用例不触重写
        executor = new EvalRunExecutor(retrievalEngine,
                (history, summary, query) -> new RewrittenQuery(query, List.of(), false),
                runMapper, docMapper, chunkMapper, new RetrieveProperties(), audit, new ObjectMapper());
    }

    private void stubDocs(Object... pairs) {
        List<KbDocument> docs = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            KbDocument doc = new KbDocument();
            doc.setId((Long) pairs[i]);
            doc.setFileName((String) pairs[i + 1]);
            docs.add(doc);
        }
        when(docMapper.selectList(any())).thenReturn(docs);
        when(chunkMapper.selectCount(any())).thenReturn(12L);
        when(docMapper.selectCount(any())).thenReturn((long) docs.size());
    }

    private void stubRetrieve(long topDocId) {
        CitedChunk hit = new CitedChunk(900L, topDocId, "doc", "content", 0.01);
        when(retrievalEngine.retrieve(anyLong(), any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(hit), List.of(hit), List.of(hit)));
    }

    private static final String SET_JSON = """
            {"id":"c1","query":"q1","expected_doc_ids":["01-参数"],"difficulty":"easy"}
            {"id":"c2","query":"q2","expected_doc_ids":["99-不存在"],"difficulty":"hard"}
            """;

    @Test
    void 完成_指标与delta正确_业务码按文件名去扩展名解析() throws Exception {
        // doc 111 文件名 01-参数.md → 业务码 01-参数；c1 命中（hit=1, recall=1, mrr=1）
        // c2 期望业务码解析不到 → -1 永不命中（hit=0, recall=0, mrr=0）
        stubDocs(111L, "01-参数.md", 222L, "02-保修.md");
        stubRetrieve(111L);
        // 上一 COMPLETED 运行：hitAt5=0.5 → delta.hitAt5 = 0.5 - 0.5 = 0.0
        EvalRun previous = new EvalRun();
        previous.setId(42L);
        previous.setStatus("COMPLETED");
        previous.setReport("{\"hitAt5\":0.5,\"recallAt5\":0.4,\"mrrAt10\":0.6}");
        when(runMapper.selectList(any())).thenReturn(List.of(previous));

        List<EvalSetSupport.EvalCase> cases = parseInline(SET_JSON);
        executor.execute(1000L, 9L, cases);

        ArgumentCaptor<EvalRun> captor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper).updateById(captor.capture());
        EvalRun patch = captor.getValue();
        assertThat(patch.getStatus()).isEqualTo("COMPLETED");
        assertThat(patch.getFinishedAt()).isNotNull();

        JsonNode report = MAPPER.readTree(patch.getReport());
        assertThat(report.path("hitAt5").asDouble()).isEqualTo(0.5);
        assertThat(report.path("recallAt5").asDouble()).isEqualTo(0.5);
        assertThat(report.path("mrrAt10").asDouble()).isEqualTo(0.5);
        assertThat(report.path("delta").path("vsLastRunId").asLong()).isEqualTo(42L);
        assertThat(report.path("delta").path("hitAt5").asDouble()).isEqualTo(0.0);
        assertThat(report.path("corpus").path("docs").asLong()).isEqualTo(2L);
        assertThat(report.path("corpus").path("chunks").asLong()).isEqualTo(12L);
        assertThat(report.path("layers").path("easy").path("cases").asInt()).isEqualTo(1);
        assertThat(report.path("layers").path("easy").path("hitAt5").asDouble()).isEqualTo(1.0);
        assertThat(report.path("layers").path("hard").path("hitAt5").asDouble()).isEqualTo(0.0);
    }

    @Test
    void 无基线时delta为null() throws Exception {
        stubDocs(111L, "01-参数.md");
        stubRetrieve(111L);
        when(runMapper.selectList(any())).thenReturn(List.of());

        executor.execute(1001L, 9L, parseInline(SET_JSON));

        ArgumentCaptor<EvalRun> captor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper).updateById(captor.capture());
        JsonNode report = MAPPER.readTree(captor.getValue().getReport());
        assertThat(report.path("delta").isNull()).isTrue();
    }

    @Test
    void 执行失败_落FAILED与原因_审计FAIL() {
        when(docMapper.selectList(any())).thenThrow(new IllegalStateException("db gone"));

        executor.execute(1002L, 9L, parseInline(SET_JSON));

        ArgumentCaptor<EvalRun> captor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper).updateById(captor.capture());
        EvalRun patch = captor.getValue();
        assertThat(patch.getStatus()).isEqualTo("FAILED");
        assertThat(patch.getErrorMsg()).contains("db gone");
        assertThat(patch.getFinishedAt()).isNotNull();

        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), actionCaptor.capture(), any(), any(),
                resultCaptor.capture(), any());
        assertThat(actionCaptor.getValue()).isEqualTo("EVAL_RUN");
        assertThat(resultCaptor.getValue()).isEqualTo("FAIL");
    }

    private List<EvalSetSupport.EvalCase> parseInline(String setJson) {
        try {
            List<EvalSetSupport.EvalCase> cases = new java.util.ArrayList<>();
            for (String line : setJson.split("\n")) {
                if (line.isBlank() || line.strip().startsWith("#")) {
                    continue;
                }
                cases.add(MAPPER.readValue(line, EvalSetSupport.EvalCase.class));
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
