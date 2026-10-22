package com.zhixing.service.agenttools;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import com.zhixing.service.KbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * search_knowledge（读，详设 13-阶段3 §6）：在知识库中检索资料片段，供回答事实性问题前取证。
 * 阶段 3 为单库形态：库 id 经 KbService.listKb() 动态取唯一库（不硬编码数据库 id），
 * 多库路由留待后续版本的参数扩展。
 */
@Slf4j
@Component
public class SearchKnowledgeTool implements Tool {

    static final String EMPTY_RESULT_TEXT = "知识库中未检索到相关内容";

    private final RetrievalEngine retrievalEngine;

    private final KbService kbService;

    private final RetrieveProperties retrieveProps;

    public SearchKnowledgeTool(RetrievalEngine retrievalEngine, KbService kbService, RetrieveProperties retrieveProps) {
        this.retrievalEngine = retrievalEngine;
        this.kbService = kbService;
        this.retrieveProps = retrieveProps;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "在用户的私有知识库（产品手册/参数表/售后政策/故障排查文档）中检索资料片段。"
                + "回答产品参数、售后政策、故障处理等事实性问题前应先调用本工具取证；"
                + "输入为检索关键词或短语（如「小米15 Ultra 防水等级」），返回最相关的知识片段及其来源文档名。"
                + "日常闲聊或与知识库无关的通用问题不要调用。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{"
                + "\"type\":\"string\",\"description\":\"检索关键词或短语，提取问题中的产品名与属性词\"}},"
                + "\"required\":[\"query\"]}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("query");
        String query = raw == null ? "" : String.valueOf(raw).trim();
        if (query.isEmpty()) {
            return ToolResult.fail("检索词不能为空，请给出要查找的产品名或属性词", "args.query 为空");
        }
        var knowledgeBases = kbService.listKb();
        if (knowledgeBases.isEmpty()) {
            return ToolResult.ok(EMPTY_RESULT_TEXT + "（知识库尚未创建）");
        }
        long kbId = knowledgeBases.get(0).getId();
        var budget = RetrieveBudget.of(retrieveProps.getVectorTopK(), retrieveProps.getKeywordTopK(),
                retrieveProps.getFinalTopK());
        // 工具路径无 trace 上下文（trace 由 Agent 链路的 run 统一记录）
        RetrievalResult detail = retrievalEngine.retrieve(kbId, query, budget, null);

        var fused = detail.fused();
        if (fused.isEmpty()) {
            return ToolResult.ok(EMPTY_RESULT_TEXT);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < fused.size(); i++) {
            var chunk = fused.get(i);
            text.append('[').append(chunk.docName()).append("] ")
                    .append(chunk.content(), 0, Math.min(200, chunk.content().length()));
            if (i < fused.size() - 1) {
                text.append('\n');
            }
        }
        log.debug("[search_knowledge] query={} 命中 {} 块", query, fused.size());
        return ToolResult.ok(text.toString());
    }
}
