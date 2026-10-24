package com.zhixing.core.ingest;

import cn.hutool.core.util.IdUtil;
import com.zhixing.common.BusinessException;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.ModelClientException;
import com.zhixing.infra.storage.FileStorage;
import com.zhixing.service.KbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 入库编排：parse → chunk → embed → write，异步执行、逐步落库状态。
 *
 * - 状态机纪律：每进入一个节点先落库 status 再干活（前端轮询可见进度）；节点内异常 → FAILED，
 *   error_msg 只放对外安全文案，堆栈留在服务端日志。
 * - 嵌入重试（DL-11 定稿：batch 级）：pipeline 按 zhixing.llm.embedding.batch-size 自行分片逐批
 *   调用 embedAll，单批失败重试 2 次（共 3 次尝试）仍败 → 整体 FAILED；已成功批次直接落位
 *   结果数组，不随后续批次失败重嵌。红线禁止改 infra/llm，故分片与重试都收在本类——
 *   批大小与客户端 maxBatchSize 同源（同一 yaml 键），单批不超限时客户端内部分片不会触发。
 * - 写入：经 {@link KbService#replaceChunks}（先删后插，单事务在 service 层）。
 * - 线程模型：submit 提交 ingest-pool；池满触发 CallerRuns 由提交线程亲自执行（天然背压），
 *   阻塞的是调用方而不是无限堆任务。
 */
@Slf4j
@Component
public class IngestionPipeline {

    /** 单批重试次数：首次 + 重试 2 次 */
    private static final int EMBED_MAX_ATTEMPTS = 3;

    private final Executor ingestPool;
    private final FileStorage fileStorage;
    private final DocumentParser documentParser;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final int embedBatchSize;
    private final KbKnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbService kbService;

    public IngestionPipeline(@Qualifier("ingest-pool") Executor ingestPool,
                             FileStorage fileStorage,
                             DocumentParser documentParser,
                             Chunker chunker,
                             EmbeddingClient embeddingClient,
                             @Value("${zhixing.llm.embedding.batch-size:16}") int embedBatchSize,
                             KbKnowledgeBaseMapper kbMapper,
                             KbDocumentMapper docMapper,
                             // @Lazy 断构造环：KbService.upload → pipeline.submit，本类 → KbService.replaceChunks
                             @Lazy KbService kbService) {
        this.ingestPool = ingestPool;
        this.fileStorage = fileStorage;
        this.documentParser = documentParser;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.embedBatchSize = embedBatchSize;
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.kbService = kbService;
    }

    public void submit(Long documentId) {
        ingestPool.execute(() -> doIngest(documentId));
    }

    private void doIngest(Long documentId) {
        KbDocument doc = docMapper.selectById(documentId);
        if (doc == null) {
            log.warn("入库跳过：文档 {} 不存在", documentId);
            return;
        }
        try {
            enter(doc, DocumentStatus.PARSING);
            String text = parseText(doc);

            enter(doc, DocumentStatus.CHUNKING);
            List<Chunk> chunks = chunker.chunk(text, chunkConfigOf(doc));
            List<KbChunk> rows = buildRows(doc, chunks);

            if (!chunks.isEmpty()) {
                enter(doc, DocumentStatus.EMBEDDING);
                List<float[]> vectors = embedAllWithRetry(documentId, contentsOf(chunks));
                for (int i = 0; i < rows.size(); i++) {
                    rows.get(i).setEmbedding(KbChunk.toVectorLiteral(vectors.get(i)));
                }
            }

            kbService.replaceChunks(documentId, rows);

            complete(doc, rows.size());
            log.info("入库完成 docId={} chunks={}", documentId, rows.size());
        } catch (Exception e) {
            log.error("入库失败 docId={}", documentId, e);
            fail(doc, safeMessage(e));
        }
    }

    /** 读盘 → 落临时文件（Tika 接口收 File）→ 解析 → 清理临时文件。 */
    private String parseText(KbDocument doc) throws IOException {
        byte[] bytes = fileStorage.read(doc.getFilePath());
        String suffix = doc.getFileType() == null ? ".bin" : "." + doc.getFileType();
        Path temp = Files.createTempFile("zhixing-parse-", suffix);
        try {
            Files.write(temp, bytes);
            return documentParser.parse(temp.toFile()).text();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 分块参数取自所属知识库配置（默认 500/50 由 DDL 声明）；库行缺失按默认兜底。 */
    private ChunkConfig chunkConfigOf(KbDocument doc) {
        KbKnowledgeBase kb = kbMapper.selectById(doc.getKbId());
        return kb == null ? ChunkConfig.DEFAULT : new ChunkConfig(kb.getChunkSize(), kb.getChunkOverlap());
    }

    private List<KbChunk> buildRows(KbDocument doc, List<Chunk> chunks) {
        List<KbChunk> rows = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            KbChunk row = new KbChunk();
            row.setId(IdUtil.getSnowflakeNextId());
            row.setKbId(doc.getKbId());
            row.setDocId(doc.getId());
            row.setChunkIndex(chunk.index());
            row.setContent(chunk.content());
            row.setTokenCount(chunk.tokenCount());
            rows.add(row);
        }
        return rows;
    }

    private List<String> contentsOf(List<Chunk> chunks) {
        return chunks.stream().map(Chunk::content).toList();
    }

    /** 按 embedBatchSize 切批顺序嵌入；某批最终失败即整体失败，已成功批次保留不重嵌。 */
    private List<float[]> embedAllWithRetry(Long documentId, List<String> texts) {
        float[][] results = new float[texts.size()][];
        int from = 0;
        while (from < texts.size()) {
            int to = Math.min(from + embedBatchSize, texts.size());
            List<float[]> batchVectors = embedBatchWithRetry(documentId, texts.subList(from, to));
            for (int i = from; i < to; i++) {
                results[i] = batchVectors.get(i - from);
            }
            from = to;
        }
        return Arrays.asList(results);
    }

    /** 单批嵌入，失败重试至多 EMBED_MAX_ATTEMPTS 次（DL-11：重试只打当前批，不打已成功批次）。 */
    private List<float[]> embedBatchWithRetry(Long documentId, List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= EMBED_MAX_ATTEMPTS; attempt++) {
            try {
                return embeddingClient.embedAll(batch);
            } catch (RuntimeException e) {
                last = e;
                log.warn("embed 批次（{}条）第 {}/{} 次失败 docId={}：{}",
                        batch.size(), attempt, EMBED_MAX_ATTEMPTS, documentId, e.getMessage());
            }
        }
        throw last;
    }

    private void enter(KbDocument doc, DocumentStatus status) {
        doc.setStatus(status.name());
        touch(doc);
        docMapper.updateById(doc);
    }

    private void complete(KbDocument doc, int chunkCount) {
        doc.setStatus(DocumentStatus.COMPLETED.name());
        doc.setChunkCount(chunkCount);
        // errorMsg 置 null 依赖 MP 默认策略跳过 null 字段——首流程 Fresh 文档本就无残留，够用
        doc.setErrorMsg(null);
        touch(doc);
        docMapper.updateById(doc);
    }

    private void fail(KbDocument doc, String safeMsg) {
        doc.setStatus(DocumentStatus.FAILED.name());
        doc.setErrorMsg(safeMsg);
        touch(doc);
        docMapper.updateById(doc);
    }

    private void touch(KbDocument doc) {
        doc.setUpdatedAt(LocalDateTime.now());
    }

    /** error_msg 落库前统一收敛为对外安全文案；真实原因只进日志（GlobalExceptionHandler 同一纪律）。 */
    private String safeMessage(Exception e) {
        if (e instanceof BusinessException be) {
            return be.getMessage();
        }
        if (e instanceof ModelClientException) {
            return "向量化服务调用失败，请稍后重试";
        }
        return "入库处理失败，请稍后重试";
    }
}
