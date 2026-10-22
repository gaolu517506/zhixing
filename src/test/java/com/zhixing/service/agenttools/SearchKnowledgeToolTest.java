package com.zhixing.service.agenttools;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.core.retrieval.RetrieveBudget;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.service.KbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** search_knowledge 单测：桩 RetrievalEngine/KbService，验证拼接格式、空结果与参数防御。 */
class SearchKnowledgeToolTest {

    private RetrievalEngine retrievalEngine;

    private KbService kbService;

    private SearchKnowledgeTool tool;

    @BeforeEach
    void setUp() {
        retrievalEngine = mock(RetrievalEngine.class);
        kbService = mock(KbService.class);
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(2110626172473077760L);
        when(kbService.listKb()).thenReturn(List.of(kb));
        tool = new SearchKnowledgeTool(retrievalEngine, kbService, new RetrieveProperties());
    }

    @Test
    void 命中时按行拼接_docName与snippet() {
        when(retrievalEngine.retrieve(anyLong(), eq("防水"), any(RetrieveBudget.class), any()))
                .thenReturn(new RetrievalResult(
                        List.of(new CitedChunk(1L, 10L, "01-param.md", "IP68 防水说明", 0.5)),
                        List.of(),
                        List.of(new CitedChunk(1L, 10L, "01-param.md", "IP68 防水说明", 0.5))));

        ToolResult result = tool.execute(Map.of("query", "防水"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("[01-param.md] IP68 防水说明");
        // 单库形态：库 id 取 listKb 的唯一库
        verify(retrievalEngine).retrieve(eq(2110626172473077760L), eq("防水"), any(RetrieveBudget.class), any());
    }

    @Test
    void 空结果_如实返回未检索到() {
        when(retrievalEngine.retrieve(anyLong(), any(), any(RetrieveBudget.class), any()))
                .thenReturn(new RetrievalResult(List.of(), List.of(), List.of()));

        ToolResult result = tool.execute(Map.of("query", "不存在的词"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo(SearchKnowledgeTool.EMPTY_RESULT_TEXT);
    }

    @Test
    void query缺失或空白_返回fail() {
        assertThat(tool.execute(Map.of()).success()).isFalse();
        assertThat(tool.execute(Map.of("query", "   ")).success()).isFalse();
        assertThat(tool.execute(null).success()).isFalse();
    }

    @Test
    void 知识库为空_如实提示() {
        when(kbService.listKb()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("query", "anything"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("知识库尚未创建");
    }
}
