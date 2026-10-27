package com.zhixing.service.agenttools;

import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.service.KbService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * list_documents 工具单测（v1.1）：「文档名 → documentId」解析途径——删除场景死结的修复件，
 * 输出必须带可直接回填 delete_document 的 documentId。
 */
class ListDocumentsToolTest {

    private final KbService kbService = mock(KbService.class);

    private final ListDocumentsTool tool = new ListDocumentsTool(kbService);

    private KbKnowledgeBase kb(long id, String name) {
        KbKnowledgeBase k = new KbKnowledgeBase();
        k.setId(id);
        k.setName(name);
        return k;
    }

    private KbDocument doc(long id, String fileName, String status) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFileName(fileName);
        d.setStatus(status);
        return d;
    }

    @Test
    void 默认列全部库且输出带documentId() {
        when(kbService.listKb()).thenReturn(List.of(kb(1L, "演示库"), kb(2L, "场景包评估库")));
        when(kbService.listDocuments(1L)).thenReturn(List.of(doc(2114516766148677632L, "99-demo-可删除演示文档.md", "COMPLETED")));
        when(kbService.listDocuments(2L)).thenReturn(List.of(
                doc(2110626174289211392L, "01-param-xiaomi-15-ultra.md", "COMPLETED"),
                doc(2110626175828520960L, "02-param-redmi-k80.md", "COMPLETED")));

        var result = tool.execute(Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.content())
                .contains("【演示库】共 1 篇")
                .contains("documentId=2114516766148677632")
                .contains("99-demo-可删除演示文档.md")
                .contains("【场景包评估库】共 2 篇")
                .contains("documentId=2110626174289211392");
    }

    @Test
    void kbName过滤未命中时如实回报() {
        when(kbService.listKb()).thenReturn(List.of(kb(1L, "演示库")));

        var result = tool.execute(Map.of("kbName", "不存在的库"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("未找到名称含「不存在的库」的知识库");
    }

    @Test
    void 空知识库返回友好提示() {
        when(kbService.listKb()).thenReturn(List.of());

        var result = tool.execute(Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("知识库尚未创建");
    }
}
