package com.zhixing.service;

import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.core.ingest.DocumentParser;
import com.zhixing.core.ingest.DocumentStatus;
import com.zhixing.core.ingest.IngestionPipeline;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 知识库应用服务：建库 / 上传 / 列表 / 详情 / 删除 / 分块写入。事务边界统一收在本类。
 *
 * 删除的磁盘语义：文件删除失败仅告警不回滚——磁盘不是事务资源，回滚已提交意图的 DB 删除
 * 只会让两边都不一致；孤儿文件可人工清理，孤儿 DB 行则污染检索。
 */
@Slf4j
@Service
public class KbService {

    private final KbKnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final FileStorage fileStorage;
    private final IngestionPipeline pipeline;

    public KbService(KbKnowledgeBaseMapper kbMapper,
                     KbDocumentMapper docMapper,
                     KbChunkMapper chunkMapper,
                     FileStorage fileStorage,
                     // @Lazy 断构造环：pipeline 持有本类（replaceChunks），本类持有 pipeline（submit）
                     @Lazy IngestionPipeline pipeline) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.fileStorage = fileStorage;
        this.pipeline = pipeline;
    }

    public KbKnowledgeBase createKb(String name, String description) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(IdUtil.getSnowflakeNextId());
        kb.setName(name);
        kb.setDescription(description);
        kbMapper.insert(kb);
        return kb;
    }

    /**
     * 上传：hash 判重（**先于落盘**，重复文件不写磁盘不建行）→ 落盘 → 建行（UPLOADED）→ 异步入库。
     * 判重与建行之间无锁，同文件并发上传理论上可双写——单用户模式风险可忽略（注释留痕）。
     */
    public UploadOutcome upload(Long kbId, MultipartFile file) {
        requireKb(kbId);

        String original = file.getOriginalFilename();
        String fileName = FileNameUtil.getName(original == null ? "" : original);
        if (file.isEmpty() || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_INVALID, "上传文件为空或文件名不合法");
        }
        String ext = FileNameUtil.extName(fileName).toLowerCase(Locale.ROOT);
        if (!DocumentParser.SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "不支持的文件类型 ." + ext + "，支持：" + String.join(" / ", DocumentParser.SUPPORTED_EXTENSIONS));
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_READ_FAILED, "读取上传内容失败：" + fileName);
        }
        String hash = DigestUtil.sha256Hex(content);

        KbDocument existing = docMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId)
                .eq(KbDocument::getFileHash, hash)
                .last("LIMIT 1"));
        if (existing != null) {
            return new UploadOutcome(existing, true);
        }

        long docId = IdUtil.getSnowflakeNextId();
        String path = fileStorage.store(docId, fileName, content);

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFilePath(path);
        doc.setFileHash(hash);
        doc.setFileType(ext);
        doc.setFileSize((long) content.length);
        doc.setStatus(DocumentStatus.UPLOADED.name());
        docMapper.insert(doc);

        pipeline.submit(docId);
        return new UploadOutcome(doc, false);
    }

    public List<KbDocument> listDocuments(Long kbId) {
        requireKb(kbId);
        return docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId)
                .orderByDesc(KbDocument::getId));
    }

    public KbDocument getDocument(Long documentId) {
        KbDocument doc = docMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND);
        }
        return doc;
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        KbDocument doc = getDocument(documentId);
        chunkMapper.deleteByDocId(documentId);
        docMapper.deleteById(documentId);
        try {
            fileStorage.delete(doc.getFilePath());
        } catch (Exception e) {
            // 见类注释：磁盘不是事务资源，失败仅告警
            log.warn("文档 {} 磁盘文件删除失败（不回滚）：{}", documentId, doc.getFilePath(), e);
        }
    }

    /**
     * 分块写入：按 docId 先删后插，单事务。由 IngestionPipeline 在嵌入完成后调用。
     * 开头复查文档仍存在——与删除并发时放弃写入，避免给已删文档留下孤儿 chunk。
     */
    @Transactional
    public void replaceChunks(Long documentId, List<KbChunk> rows) {
        if (docMapper.selectById(documentId) == null) {
            log.warn("文档 {} 已删除，放弃分块写入", documentId);
            return;
        }
        chunkMapper.deleteByDocId(documentId);
        if (!rows.isEmpty()) {
            chunkMapper.insertBatch(rows);
        }
    }

    private void requireKb(Long kbId) {
        if (kbMapper.selectById(kbId) == null) {
            throw new BusinessException(ErrorCode.KB_NOT_FOUND);
        }
    }

    /** 上传结果：idempotent=true 表示同库同 hash 命中已有文档（响应头 X-Idempotent-Hit: true）。 */
    public record UploadOutcome(KbDocument document, boolean idempotent) {
    }
}
