package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.service.KbService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库 API（契约见详设-阶段1 §6）。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    @PostMapping("/kb")
    public Result<KbCreatedResponse> createKb(@Valid @RequestBody CreateKbRequest request) {
        KbKnowledgeBase kb = kbService.createKb(request.name(), request.description());
        return Result.ok(new KbCreatedResponse(kb.getId()));
    }

    /** 库列表（前端知识库选择器），创建时间倒序。 */
    @GetMapping("/kb")
    public Result<List<KbItemResponse>> listKb() {
        return Result.ok(kbService.listKb().stream().map(KbItemResponse::of).toList());
    }

    /** 删库：级联清 chunk/文档/磁盘目录（R1-BE）。 */
    @DeleteMapping("/kb/{kbId}")
    public Result<Void> deleteKb(@PathVariable Long kbId) {
        kbService.deleteKb(kbId);
        return Result.ok(null);
    }

    /** 幂等命中时响应头带 X-Idempotent-Hit: true（详设 §6）。 */
    @PostMapping("/kb/{kbId}/documents")
    public Result<UploadDocumentResponse> uploadDocument(@PathVariable Long kbId,
                                                         @RequestPart("file") MultipartFile file,
                                                         HttpServletResponse response) {
        KbService.UploadOutcome outcome = kbService.upload(kbId, file);
        if (outcome.idempotent()) {
            response.setHeader("X-Idempotent-Hit", "true");
        }
        return Result.ok(new UploadDocumentResponse(outcome.document().getId(), outcome.document().getStatus()));
    }

    @GetMapping("/kb/{kbId}/documents")
    public Result<List<DocumentItemResponse>> listDocuments(@PathVariable Long kbId) {
        return Result.ok(kbService.listDocuments(kbId).stream().map(DocumentItemResponse::of).toList());
    }

    @GetMapping("/documents/{id}")
    public Result<DocumentItemResponse> getDocument(@PathVariable Long id) {
        return Result.ok(DocumentItemResponse.of(kbService.getDocument(id)));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        kbService.deleteDocument(id);
        return Result.ok(null);
    }

    public record CreateKbRequest(
            @NotBlank(message = "name 不能为空") @Size(max = 64, message = "name 长度不能超过 64") String name,
            @Size(max = 512, message = "description 长度不能超过 512") String description) {
    }

    public record KbCreatedResponse(Long id) {
    }

    public record KbItemResponse(Long id, String name, String description, LocalDateTime createdAt) {

        static KbItemResponse of(KbKnowledgeBase kb) {
            return new KbItemResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
        }
    }

    public record UploadDocumentResponse(Long documentId, String status) {
    }

    public record DocumentItemResponse(Long id, String fileName, String fileType, Long fileSize,
                                       String status, Integer chunkCount, String errorMsg) {

        static DocumentItemResponse of(KbDocument doc) {
            return new DocumentItemResponse(doc.getId(), doc.getFileName(), doc.getFileType(),
                    doc.getFileSize(), doc.getStatus(), doc.getChunkCount(), doc.getErrorMsg());
        }
    }
}
