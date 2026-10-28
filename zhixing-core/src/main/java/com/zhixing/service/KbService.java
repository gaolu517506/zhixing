package com.zhixing.service;

import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.core.ingest.DocumentParser;
import com.zhixing.core.ingest.DocumentStatus;
import com.zhixing.core.ingest.IngestionPipeline;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import com.zhixing.infra.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库应用服务：建库 / 上传 / 列表 / 详情 / 删除 / 分块写入。事务边界统一收在本类。
 *
 * <p>权限（02-技术方案 §B2，D14）：本类全部用户面方法在入口经 {@link PermissionService}
 * 判定（controller 层零权限判断），Agent 工具（list_documents / delete_document 等）经
 * AgentService.launch 的身份传播同样携带请求身份，写路径与 HTTP 口同权。
 * 列表以 {@link PermissionService#accessibleKbIds()} 收敛（ALL ∪ 直属成员，单点口径）。
 *
 * <p>审计（02-技术方案 §B3）：建库/删库/上传/删文档四类事件在动作生效后落审计，
 * 记录失败由 AuditRecorder 自吞，不反噬业务。
 *
 * <p>删除的磁盘语义：文件删除失败仅告警不回滚——磁盘不是事务资源，回滚已提交意图的 DB 删除
 * 只会让两边都不一致；孤儿文件可人工清理，孤儿 DB 行则污染检索。
 */
@Slf4j
@Service
public class KbService {

    private static final String ROLE_KB_ADMIN = "ADMIN";

    private final KbKnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final KbMemberMapper memberMapper;
    private final FileStorage fileStorage;
    private final IngestionPipeline pipeline;
    private final PermissionService permissionService;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public KbService(KbKnowledgeBaseMapper kbMapper,
                     KbDocumentMapper docMapper,
                     KbChunkMapper chunkMapper,
                     KbMemberMapper memberMapper,
                     FileStorage fileStorage,
                     // @Lazy 断构造环：pipeline 持有本类（replaceChunks），本类持有 pipeline（submit）
                     @Lazy IngestionPipeline pipeline,
                     PermissionService permissionService,
                     AuditRecorder audit,
                     ObjectMapper objectMapper) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.memberMapper = memberMapper;
        this.fileStorage = fileStorage;
        this.pipeline = pipeline;
        this.permissionService = permissionService;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * 建库：visibility 落 DDL 默认 PRIVATE；创建人自动登记为库 ADMIN（否则 PRIVATE 库
     * 连创建人自己都进不去）。登录用户皆可建库（认证由 AuthFilter 保证）。
     */
    @Transactional
    public KbKnowledgeBase createKb(String name, String description) {
        CurrentUser user = AuthContext.current();
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(IdUtil.getSnowflakeNextId());
        kb.setName(name);
        kb.setDescription(description);
        kbMapper.insert(kb);
        KbMember member = new KbMember();
        member.setKbId(kb.getId());
        member.setUserId(user.id());
        member.setRole(ROLE_KB_ADMIN);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        audit.record(user.id(), user.username(), AuditAction.KB_CREATE.name(),
                "KB", String.valueOf(kb.getId()), AuditResult.OK, detailJson("name", name));
        return kb;
    }

    /**
     * 上传：入口过库权限（成员即可写文档；删库才是库管理员动作）→ hash 判重（**先于落盘**，
     * 重复文件不写磁盘不建行）→ 落盘 → 建行（UPLOADED）→ 异步入库。
     * 判重与建行之间无锁，同文件并发上传理论上可双写——单用户模式风险可忽略（注释留痕）。
     */
    public UploadOutcome upload(Long kbId, MultipartFile file) {
        requireKb(kbId);
        permissionService.requireAccess(kbId);

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
            recordUpload(kbId, existing.getId(), fileName, true);
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
        recordUpload(kbId, docId, fileName, false);
        return new UploadOutcome(doc, false);
    }

    /** 库文档清单：入口过库权限（SSE/下载同权语义——检查在 service 入口）。 */
    public List<KbDocument> listDocuments(Long kbId) {
        requireKb(kbId);
        permissionService.requireAccess(kbId);
        return docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId)
                .orderByDesc(KbDocument::getId));
    }

    /** 文档详情（含下载取数路径）：按文档归属库过权限。 */
    public KbDocument getDocument(Long documentId) {
        KbDocument doc = docMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND);
        }
        permissionService.requireAccess(doc.getKbId());
        return doc;
    }

    /** 删文档：权限由 getDocument 按归属库判定（Agent 工具路径同样经此收敛）。 */
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
        recordDocDelete(doc.getKbId(), documentId, doc.getFileName());
    }

    // ===== v0.1 收尾线新增（R1-BE）=====

    /**
     * 库列表（前端聊天页知识库选择器 / Agent 工具的库清单）：以
     * {@link PermissionService#accessibleKbIds()} 收敛为「ALL ∪ 直属成员」，创建时间倒序。
     */
    public List<KbKnowledgeBase> listKb() {
        List<Long> accessible = permissionService.accessibleKbIds();
        if (accessible.isEmpty()) {
            return List.of();
        }
        return kbMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>()
                .in(KbKnowledgeBase::getId, accessible)
                .orderByDesc(KbKnowledgeBase::getCreatedAt)
                .orderByDesc(KbKnowledgeBase::getId));
    }

    /**
     * 删库（库管理员动作，02 §B2 requireKbAdmin）：级联 kb_chunk → kb_document →
     * 磁盘文件目录 → kb_knowledge_base，单事务。
     * 磁盘沿用 {@link #deleteDocument} 的既有语义：失败仅告警不回滚（磁盘不是事务资源），
     * 孤儿文件可人工清理，DB 行为准。
     */
    @Transactional
    public void deleteKb(Long kbId) {
        requireKb(kbId);
        permissionService.requireKbAdmin(kbId);
        List<KbDocument> docs = docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId));
        for (KbDocument doc : docs) {
            chunkMapper.deleteByDocId(doc.getId());
            docMapper.deleteById(doc.getId());
            // 文件删除与目录递归清理各自兜底：前者失败后者仍执行（递归清理会连带删掉文件）
            try {
                fileStorage.delete(doc.getFilePath());
            } catch (Exception e) {
                log.warn("文档 {} 磁盘文件删除失败（不回滚）：{}", doc.getId(), doc.getFilePath(), e);
            }
            try {
                fileStorage.deleteDocDir(doc.getId());
            } catch (Exception e) {
                log.warn("文档 {} 磁盘目录清理失败（不回滚）：{}", doc.getId(), doc.getId(), e);
            }
        }
        kbMapper.deleteById(kbId);
        CurrentUser user = AuthContext.currentOrNull();
        audit.record(user == null ? null : user.id(), user == null ? null : user.username(),
                AuditAction.KB_DELETE.name(), "KB", String.valueOf(kbId), AuditResult.OK, null);
    }

    /**
     * 分块写入：按 docId 先删后插，单事务。由 IngestionPipeline 在嵌入完成后调用
     * （入库流水线内部路径，非用户面——不做权限判定，无请求身份）。
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

    private void recordUpload(Long kbId, Long docId, String fileName, boolean idempotent) {
        CurrentUser user = AuthContext.currentOrNull();
        audit.record(user == null ? null : user.id(), user == null ? null : user.username(),
                AuditAction.DOC_UPLOAD.name(), "DOCUMENT", String.valueOf(docId), AuditResult.OK,
                detailJson("kbId", kbId, "fileName", fileName, "idempotent", idempotent));
    }

    private void recordDocDelete(Long kbId, Long docId, String fileName) {
        CurrentUser user = AuthContext.currentOrNull();
        audit.record(user == null ? null : user.id(), user == null ? null : user.username(),
                AuditAction.DOC_DELETE.name(), "DOCUMENT", String.valueOf(docId), AuditResult.OK,
                detailJson("kbId", kbId, "fileName", fileName));
    }

    /** 审计 detail 安全序列化（文件名等用户可控字符串不过手拼）；失败降级 null 不反噬业务。 */
    private String detailJson(Object... keyValuePairs) {
        try {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
                map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    /** 上传结果：idempotent=true 表示同库同 hash 命中已有文档（响应头 X-Idempotent-Hit: true）。 */
    public record UploadOutcome(KbDocument document, boolean idempotent) {
    }
}
