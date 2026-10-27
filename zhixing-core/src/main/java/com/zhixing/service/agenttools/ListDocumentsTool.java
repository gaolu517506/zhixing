package com.zhixing.service.agenttools;

import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.service.KbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * list_documents（读，v1.1）：列出知识库文档清单（documentId/文件名/状态）——删除等写操作
 * 前置的「文档名 → documentId」解析途径。缺此工具时模型只能向用户索要数字 ID 或拿文件名
 * 猜测（2026-10-26 用户实测暴露的死结：猜 documentId=99 删除失败）。默认列全部库（多库形态，
 * 与单库形态的 search_knowledge 不同：本工具天然需要跨库视角），可选 kbName 包含匹配过滤。
 */
@Slf4j
@Component
public class ListDocumentsTool implements Tool {

    private final KbService kbService;

    public ListDocumentsTool(KbService kbService) {
        this.kbService = kbService;
    }

    @Override
    public String name() {
        return "list_documents";
    }

    @Override
    public String description() {
        return "列出知识库中的文档清单（文档 ID、文件名、状态）。当用户要求删除或操作某份文档"
                + "而你还不知道其文档 ID 时，先用本工具查询拿到 documentId，不要向用户索要数字 ID，"
                + "也不要用文件名猜测 ID。可选参数 kbName 按库名包含匹配过滤（如「演示库」），"
                + "不传则列出全部库。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{\"kbName\":{"
                + "\"type\":\"string\",\"description\":\"按知识库名称包含匹配过滤，可选\"}},"
                + "\"required\":[]}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("kbName");
        String kbName = raw == null ? "" : String.valueOf(raw).trim();

        List<KbKnowledgeBase> kbs = kbService.listKb();
        if (kbs.isEmpty()) {
            return ToolResult.ok("知识库尚未创建");
        }

        StringBuilder text = new StringBuilder();
        int matchedKbs = 0;
        int totalDocs = 0;
        for (KbKnowledgeBase kb : kbs) {
            if (!kbName.isEmpty() && !kb.getName().contains(kbName)) {
                continue;
            }
            matchedKbs++;
            List<KbDocument> docs = kbService.listDocuments(kb.getId());
            totalDocs += docs.size();
            text.append("【").append(kb.getName()).append("】共 ").append(docs.size()).append(" 篇\n");
            for (KbDocument doc : docs) {
                text.append("  - documentId=").append(doc.getId())
                        .append("  ").append(doc.getFileName())
                        .append("（").append(doc.getStatus()).append("）\n");
            }
        }
        if (matchedKbs == 0) {
            return ToolResult.ok("未找到名称含「" + kbName + "」的知识库");
        }
        log.debug("[list_documents] kbName={} 命中 {} 库 {} 篇", kbName, matchedKbs, totalDocs);
        return ToolResult.ok(text.toString());
    }
}
