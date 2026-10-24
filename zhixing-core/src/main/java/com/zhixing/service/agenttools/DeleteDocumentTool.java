package com.zhixing.service.agenttools;

import com.zhixing.common.BusinessException;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.service.KbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * delete_document（写，详设 13-阶段3 §6）：删除知识库文档及其全部分块，requireConfirm=true——
 * 执行前必经 ConfirmManager 人工确认（挂起恢复流）。存在性校验在执行前完成：
 * 不存在/已删除返回 fail 文案（回填给模型自纠），不做静默成功。
 */
@Slf4j
@Component
public class DeleteDocumentTool implements Tool {

    static final String NOT_FOUND_TEXT = "文档不存在或已删除";

    private final KbService kbService;

    public DeleteDocumentTool(KbService kbService) {
        this.kbService = kbService;
    }

    @Override
    public String name() {
        return "delete_document";
    }

    @Override
    public String description() {
        return "删除知识库中的一份文档（连同其全部分块与向量，不可恢复）。这是写操作，执行前需要用户确认。"
                + "仅当用户明确要求删除某份文档、且能给出文档 ID（或可从上下文确定的文档）时调用；"
                + "用户只是抱怨某个文档内容不对时不要调用。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{\"documentId\":{"
                + "\"type\":\"string\",\"description\":\"要删除的文档 ID（数字）\"}},"
                + "\"required\":[\"documentId\"]}";
    }

    @Override
    public boolean requireConfirm() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("documentId");
        long documentId;
        try {
            // Long.parseLong 防御：模型可能给出 "12.3"、"abc" 或数字对象
            documentId = Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception e) {
            return ToolResult.fail("documentId 不合法：应为数字形式的文档 ID",
                    "documentId 解析失败，raw=" + raw);
        }

        KbDocument document;
        try {
            document = kbService.getDocument(documentId);
        } catch (BusinessException e) {
            // 执行前存在性校验：不存在/已删除如实回报，让模型告知用户而不是假成功
            return ToolResult.fail(NOT_FOUND_TEXT, "getDocument 未命中: " + e.getMessage());
        }

        kbService.deleteDocument(documentId);
        log.info("[delete_document] 已删除文档 id={} fileName={}", documentId, document.getFileName());
        return ToolResult.ok("已删除文档 " + document.getFileName());
    }
}
