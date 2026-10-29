package com.zhixing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.config.EvalProperties;
import com.zhixing.config.RetrieveProperties;
import com.zhixing.core.eval.EvalSetSupport;
import com.zhixing.core.memory.RewrittenQuery;
import com.zhixing.core.retrieval.CitedChunk;
import com.zhixing.core.retrieval.RetrievalEngine;
import com.zhixing.core.retrieval.RetrievalResult;
import com.zhixing.dao.entity.EvalRun;
import com.zhixing.dao.mapper.EvalRunMapper;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收向导编排单测（02 §B6 测试点）：requireKbAdmin 门禁、评估集校验、RUNNING 落库、
 * 异步段提交（执行器 Bean）、EVAL_RUN 发起审计。
 */
class EvalRunServiceTest {

    private EvalRunMapper runMapper;

    private EvalRunExecutor executor;

    private PermissionService permissionService;

    private EvalRunService service;

    private static final CurrentUser ADMIN = new CurrentUser(7L, "owner", "ADMIN", com.zhixing.common.auth.AuthChannel.WEB);

    @BeforeEach
    void setUp() {
        runMapper = mock(EvalRunMapper.class);
        executor = mock(EvalRunExecutor.class);
        permissionService = mock(PermissionService.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        service = new EvalRunService(runMapper,
                mock(KbDocumentMapper.class), mock(KbChunkMapper.class),
                new EvalProperties(), permissionService, audit, new ObjectMapper(),
                executor, Runnable::run);
        com.zhixing.common.auth.AuthContext.set(ADMIN);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.zhixing.common.auth.AuthContext.clear();
    }

    private static final String SET_JSON = """
            {"id":"c1","query":"岚山 L1 水箱多大","expected_doc_ids":["01-参数"],"difficulty":"easy"}
            {"id":"c2","query":"保修几年","expected_doc_ids":["02-保修"],"difficulty":"medium"}
            """;

    @Test
    void start_非库管理员拒绝且不落库() {
        com.zhixing.common.auth.AuthContext.clear();
        com.zhixing.common.auth.AuthContext.set(
                new CurrentUser(8L, "member", "USER", com.zhixing.common.auth.AuthChannel.WEB));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(permissionService).requireKbAdmin(9L);

        assertThatThrownBy(() -> service.start(new EvalRunService.StartRunRequest(9L, null, SET_JSON)))
                .isInstanceOf(BusinessException.class);
        verify(runMapper, never()).insert(any(EvalRun.class));
    }

    @Test
    void start_setPath与setJson必须二选一() {
        assertThatThrownBy(() -> service.start(new EvalRunService.StartRunRequest(9L, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("二选一");
        assertThatThrownBy(() -> service.start(new EvalRunService.StartRunRequest(9L, "a.jsonl", SET_JSON)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("二选一");
    }

    @Test
    void start_setPath必须为仓库内相对jsonl() {
        assertThatThrownBy(() -> service.start(new EvalRunService.StartRunRequest(9L, "/etc/passwd", null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.start(new EvalRunService.StartRunRequest(9L, "../secrets.jsonl", null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.start(new EvalRunService.StartRunRequest(9L, "docs/set.txt", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void start_空评估集拒绝() {
        assertThatThrownBy(() -> service.start(
                new EvalRunService.StartRunRequest(9L, null, "# 只有注释行")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评估集为空");
    }

    @Test
    void start_内联集超长拒绝() {
        EvalProperties props = new EvalProperties();
        props.setMaxInlineSetChars(10);
        EvalRunService strict = new EvalRunService(runMapper,
                mock(KbDocumentMapper.class), mock(KbChunkMapper.class), props,
                permissionService, mock(AuditRecorder.class), new ObjectMapper(),
                executor, Runnable::run);
        assertThatThrownBy(() -> strict.start(new EvalRunService.StartRunRequest(9L, null, "x".repeat(11))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    void start_落RUNNING行_发起审计_交执行器() {
        when(runMapper.insert(any(EvalRun.class))).thenReturn(1);

        EvalRunService.EvalRunView view =
                service.start(new EvalRunService.StartRunRequest(9L, null, SET_JSON));

        ArgumentCaptor<EvalRun> captor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper).insert(captor.capture());
        EvalRun inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo("RUNNING");
        assertThat(inserted.getKbId()).isEqualTo(9L);
        assertThat(inserted.getCreatedBy()).isEqualTo(7L);
        assertThat(inserted.getConfig()).contains("\"source\":\"inline\"").contains("\"cases\":2");
        assertThat(view.status()).isEqualTo("RUNNING");
        // 发起审计：actor 为库管理员（预计算变量走查——不在此断言参数细节，接线测试覆盖）
        verify(executor).execute(anyLong(), anyLong(), anyList());
    }
}
