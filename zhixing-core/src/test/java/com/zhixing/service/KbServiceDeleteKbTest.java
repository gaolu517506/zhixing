package com.zhixing.service;

import com.zhixing.common.BusinessException;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.core.ingest.IngestionPipeline;
import com.zhixing.infra.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KB 删库级联单测（任务书 §2）：桩 mapper 断言级联调用顺序（chunk → doc → 磁盘 → kb 行），
 * 库不存在抛 K1001，磁盘删除异常不回滚（删除仍完成）。
 */
class KbServiceDeleteKbTest {

    private KbKnowledgeBaseMapper kbMapper;

    private KbDocumentMapper docMapper;

    private KbChunkMapper chunkMapper;

    private FileStorage fileStorage;

    private KbService service;

    @BeforeEach
    void setUp() {
        kbMapper = mock(KbKnowledgeBaseMapper.class);
        docMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        fileStorage = mock(FileStorage.class);
        service = new KbService(kbMapper, docMapper, chunkMapper, mock(com.zhixing.dao.mapper.KbMemberMapper.class),
                fileStorage, mock(IngestionPipeline.class), mock(PermissionService.class),
                mock(AuditRecorder.class), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void stubKb(long kbId, KbDocument... docs) {
        when(kbMapper.selectById(kbId)).thenReturn(new KbKnowledgeBase());
        when(docMapper.selectList(any())).thenReturn(List.of(docs));
    }

    private static KbDocument doc(long id, String path) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilePath(path);
        return d;
    }

    @Test
    void 删库级联_顺序chunk到doc到磁盘到kb行() {
        stubKb(1L, doc(11L, "11/a.pdf"), doc(12L, "12/b.txt"));

        service.deleteKb(1L);

        InOrder order = inOrder(chunkMapper, docMapper, fileStorage, kbMapper);
        order.verify(chunkMapper).deleteByDocId(11L);
        order.verify(docMapper).deleteById(11L);
        order.verify(fileStorage).delete("11/a.pdf");
        order.verify(fileStorage).deleteDocDir(11L);
        order.verify(chunkMapper).deleteByDocId(12L);
        order.verify(docMapper).deleteById(12L);
        order.verify(fileStorage).delete("12/b.txt");
        order.verify(fileStorage).deleteDocDir(12L);
        order.verify(kbMapper).deleteById(1L);
    }

    @Test
    void 库不存在_抛K1001() {
        when(kbMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteKb(404L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code()).isEqualTo("K1001"));
    }

    @Test
    void 磁盘删除异常不回滚_DB删除仍完成() {
        stubKb(1L, doc(11L, "11/a.pdf"));
        doThrow(new UncheckedIOException("disk gone", new IOException("io")))
                .when(fileStorage).delete("11/a.pdf");

        assertThatCode(() -> service.deleteKb(1L)).doesNotThrowAnyException();

        // 磁盘挂了不拦 DB 级联：doc 行与 kb 行都删掉
        verify(docMapper).deleteById(11L);
        verify(kbMapper).deleteById(1L);
        verify(fileStorage).deleteDocDir(11L);
    }

    @Test
    void 无文档的空库_只删kb行() {
        stubKb(1L);

        service.deleteKb(1L);

        verify(chunkMapper, org.mockito.Mockito.never()).deleteByDocId(any());
        verify(docMapper, org.mockito.Mockito.never()).deleteById(any(KbDocument.class));
        verify(kbMapper).deleteById(1L);
    }
}
