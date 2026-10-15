package com.zhixing.core.ingest;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.ErrorType;
import com.zhixing.infra.llm.ModelClientException;
import com.zhixing.infra.storage.FileStorage;
import com.zhixing.service.KbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库状态机单测：不联网、不起 Spring。executor 用同步直跑（Runnable::run），
 * submit 返回即 doIngest 已完成，直接断言终态；Mapper/Service 用 Mockito 桩。
 *
 * 状态断言在 updateById 调用时序记录（statusLog）而非参数捕获——pipeline 复用同一实体
 * 逐步 setStatus，捕获到的引用永远是终值，只有调用时点快照才反映"先落库再干活"的顺序。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionPipelineTest {

    private static final long DOC_ID = 1L;
    private static final long KB_ID = 100L;

    @Mock private KbKnowledgeBaseMapper kbMapper;
    @Mock private KbDocumentMapper docMapper;
    @Mock private KbChunkMapper chunkMapper;
    @Mock private KbService kbService;
    @Mock private EmbeddingClient embeddingClient;

    @Captor private ArgumentCaptor<List<KbChunk>> rowsCaptor;

    /** 每次状态落库的时点快照（MockitoExtension 每用例重建实例，字段天然隔离） */
    private final List<String> statusLog = new ArrayList<>();

    private final Map<String, byte[]> files = new HashMap<>();
    private final FileStorage storage = new FileStorage() {
        @Override
        public String store(long docId, String fileName, byte[] content) {
            String path = docId + "/" + fileName;
            files.put(path, content);
            return path;
        }

        @Override
        public byte[] read(String path) {
            byte[] bytes = files.get(path);
            if (bytes == null) {
                throw new AssertionError("测试桩中文件不存在：" + path);
            }
            return bytes;
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }
    };

    private DocumentParser parser;
    private IngestionPipeline pipeline;
    private KbDocument doc;

    @BeforeEach
    void setUp() {
        // 解析器桩：三段各 250 字，经 ChunkConfig(500,50) 产出 2 块
        parser = new DocumentParser() {
            @Override
            public boolean supports(String fileName, String mimeType) {
                return true;
            }

            @Override
            public ParsedDocument parse(File file) {
                // 三段各 250 字、内容互异——全同文本会让规则⑤的"重复残余丢弃"把真实末块误判为重叠尾巴
                return ParsedDocument.of(String.join("\n",
                        "A".repeat(250), "B".repeat(250), "C".repeat(250)));
            }
        };
        pipeline = new IngestionPipeline(Runnable::run, storage, parser, new FixedSizeChunker(),
                embeddingClient, kbMapper, docMapper, kbService);

        doc = freshDoc();
        when(docMapper.selectById(DOC_ID)).thenReturn(doc);
        when(docMapper.updateById(any(KbDocument.class))).thenAnswer(inv -> {
            statusLog.add(((KbDocument) inv.getArgument(0)).getStatus());
            return 1;
        });

        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(KB_ID);
        kb.setChunkSize(500);
        kb.setChunkOverlap(50);
        when(kbMapper.selectById(KB_ID)).thenReturn(kb);

        when(embeddingClient.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1024]).toList();
        });
    }

    private KbDocument freshDoc() {
        KbDocument doc = new KbDocument();
        doc.setId(DOC_ID);
        doc.setKbId(KB_ID);
        doc.setFileName("a.txt");
        doc.setFilePath("1/a.txt");
        doc.setFileType("txt");
        doc.setStatus(DocumentStatus.UPLOADED.name());
        files.put("1/a.txt", "any-bytes".getBytes());
        return doc;
    }

    @Test
    void 成功路径_状态依序流转到COMPLETED并写入分块() {
        pipeline.submit(DOC_ID);

        // 规格②：每进入一个节点先落库 status——PARSING/CHUNKING/EMBEDDING 依次出现，终态 COMPLETED
        assertThat(statusLog).containsExactly("PARSING", "CHUNKING", "EMBEDDING", "COMPLETED");
        assertThat(doc.getStatus()).isEqualTo("COMPLETED");
        assertThat(doc.getChunkCount()).isEqualTo(2);

        verify(kbService).replaceChunks(eq(DOC_ID), rowsCaptor.capture());
        List<KbChunk> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(rows.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(rows.get(0).getEmbedding()).startsWith("[").endsWith("]");
        assertThat(rows.get(1).getTokenCount()).isGreaterThan(0);
        // 先删后插是 service 事务方法的事，pipeline 不直接碰 chunkMapper
        verify(chunkMapper, never()).insertBatch(anyList());
    }

    @Test
    void 嵌入三次尝试仍失败_整体FAILED且不写分块() {
        AtomicInteger attempts = new AtomicInteger();
        when(embeddingClient.embedAll(anyList())).thenAnswer(inv -> {
            attempts.incrementAndGet();
            throw new ModelClientException(ErrorType.SERVER_ERROR, "zhipu 500");
        });

        pipeline.submit(DOC_ID);

        // 规格④：重试粒度是整次 embedAll，共 3 次尝试（DL-11 候选偏差）
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(statusLog).containsExactly("PARSING", "CHUNKING", "EMBEDDING", "FAILED");
        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMsg()).isEqualTo("向量化服务调用失败，请稍后重试");
        verify(kbService, never()).replaceChunks(any(), anyList());
        verify(chunkMapper, never()).insertBatch(anyList());
    }

    @Test
    void 解析失败_FAILED并保留业务安全文案() {
        DocumentParser brokenParser = new DocumentParser() {
            @Override
            public boolean supports(String fileName, String mimeType) {
                return true;
            }

            @Override
            public ParsedDocument parse(File file) {
                throw new BusinessException(ErrorCode.FILE_READ_FAILED, "文档解析失败：a.txt");
            }
        };
        pipeline = new IngestionPipeline(Runnable::run, storage, brokenParser, new FixedSizeChunker(),
                embeddingClient, kbMapper, docMapper, kbService);

        pipeline.submit(DOC_ID);

        assertThat(statusLog).containsExactly("PARSING", "FAILED");
        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMsg()).isEqualTo("文档解析失败：a.txt");
    }

    @Test
    void 文档已删除_静默跳过不落任何状态() {
        when(docMapper.selectById(DOC_ID)).thenReturn(null);

        pipeline.submit(DOC_ID);

        verify(docMapper, never()).updateById(any(KbDocument.class));
        assertThat(statusLog).isEmpty();
    }
}
