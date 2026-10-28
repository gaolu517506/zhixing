package com.zhixing.service;

import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.memory.QueryRewriter;
import com.zhixing.core.retrieval.RetrievalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测旁路权限接线（02 §B2 调用点清单）：EvalService 入口必须先过库权限——
 * X-Eval-Key 是防误用门，不能替代资源授权。
 */
class EvalServicePermissionTest {

    private RetrievalEngine retrievalEngine;

    private PermissionService permissionService;

    private EvalService service;

    @BeforeEach
    void setUp() {
        retrievalEngine = mock(RetrievalEngine.class);
        permissionService = mock(PermissionService.class);
        service = new EvalService(retrievalEngine, new RetrieveProperties(),
                mock(QueryRewriter.class), permissionService);
    }

    @Test
    void retrieve_入口先过库权限() {
        when(retrievalEngine.retrieve(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any()))
                .thenReturn(new com.zhixing.core.retrieval.RetrievalResult(java.util.List.of(), java.util.List.of(), java.util.List.of()));

        service.retrieve(new EvalService.EvalRetrieveRequest(9L, "q", null, null, null));

        verify(permissionService).requireAccess(9L);
    }

    @Test
    void retrieve_无权限阻断在检索之前() {
        org.mockito.Mockito.doThrow(new com.zhixing.common.BusinessException(
                        com.zhixing.common.ErrorCode.FORBIDDEN))
                .when(permissionService).requireAccess(9L);

        assertThatThrownBy(() -> service.retrieve(
                new EvalService.EvalRetrieveRequest(9L, "q", null, null, null)))
                .isInstanceOf(com.zhixing.common.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(retrievalEngine);
    }
}
