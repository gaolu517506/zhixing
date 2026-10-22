package com.zhixing.service.agenttools;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.core.agent.ToolResult;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.service.KbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** delete_document 单测：参数防御（非法/缺失）、存在性校验、确认标记。 */
class DeleteDocumentToolTest {

    private KbService kbService;

    private DeleteDocumentTool tool;

    @BeforeEach
    void setUp() {
        kbService = mock(KbService.class);
        tool = new DeleteDocumentTool(kbService);
    }

    private static KbDocument document(long id, String fileName) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setFileName(fileName);
        return doc;
    }

    @Test
    void 文档存在_删除成功并回报文件名() {
        when(kbService.getDocument(5L)).thenReturn(document(5L, "handbook.pdf"));

        ToolResult result = tool.execute(Map.of("documentId", "5"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("已删除文档 handbook.pdf");
        verify(kbService).deleteDocument(5L);
    }

    @Test
    void 文档不存在_返回fail文案且不执行删除() {
        when(kbService.getDocument(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.DOC_NOT_FOUND));

        ToolResult result = tool.execute(Map.of("documentId", "999"));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).isEqualTo(DeleteDocumentTool.NOT_FOUND_TEXT);
        verify(kbService, never()).deleteDocument(anyLong());
    }

    @Test
    void documentId非法_返回fail不触碰服务() {
        assertThat(tool.execute(Map.of("documentId", "abc")).success()).isFalse();
        assertThat(tool.execute(Map.of("documentId", "12.3")).success()).isFalse();
        assertThat(tool.execute(Map.of()).success()).isFalse();
        assertThat(tool.execute(null).success()).isFalse();
        verify(kbService, never()).getDocument(anyLong());
        verify(kbService, never()).deleteDocument(anyLong());
    }

    @Test
    void 契约字段_写操作必须要求确认() {
        assertThat(tool.name()).isEqualTo("delete_document");
        assertThat(tool.requireConfirm()).isTrue();
        assertThat(tool.parametersSchema()).contains("documentId");
    }
}
