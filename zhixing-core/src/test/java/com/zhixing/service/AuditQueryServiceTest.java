package com.zhixing.service;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.mapper.AuditEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计查询/导出单测（02 §B3）：仅系统 ADMIN（门禁在 service 入口）、分页、
 * CSV 转义（逗号/引号/换行）与公式前缀防护。
 */
class AuditQueryServiceTest {

    private AuditEventMapper mapper;

    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditEventMapper.class);
        service = new AuditQueryService(mapper);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private void loginAs(String role) {
        AuthContext.set(new CurrentUser(1L, "tester", role, AuthChannel.WEB));
    }

    private AuditEvent event(Long id, String action, String actorName, String detail) {
        AuditEvent e = new AuditEvent();
        e.setId(id);
        e.setActorId(7L);
        e.setActorName(actorName);
        e.setAction(action);
        e.setTargetType("KB");
        e.setTargetId("9");
        e.setResult("OK");
        e.setDetail(detail);
        e.setCreatedAt(LocalDateTime.of(2026, 10, 28, 10, 0));
        return e;
    }

    @Test
    void page_非系统管理员A0006且不触库() {
        loginAs("USER");
        assertThatThrownBy(() -> service.page(new AuditQueryService.AuditQuery(null, null, null, null), 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void page_管理员分页() {
        loginAs("ADMIN");
        when(mapper.selectList(any())).thenReturn(List.of(event(1L, "LOGIN", "a", null)));
        when(mapper.selectCount(any())).thenReturn(1L);

        AuditQueryService.AuditPage page =
                service.page(new AuditQueryService.AuditQuery(null, null, null, "LOGIN"), 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).action()).isEqualTo("LOGIN");
    }

    @Test
    void exportCsv_表头与字段转义() throws Exception {
        loginAs("ADMIN");
        when(mapper.selectList(any())).thenReturn(List.of(
                event(1L, "DOC_UPLOAD", "plain", null),
                event(2L, "KB_CREATE", "evil, \"name\"", "{\"note\":\"含,逗号\"}"),
                event(3L, "LOGIN", "=cmd|' /C calc'!A0", null)));
        StringWriter out = new StringWriter();

        long rows = service.exportCsv(new AuditQueryService.AuditQuery(null, null, null, null), out);

        String csv = out.toString();
        assertThat(rows).isEqualTo(3);
        assertThat(csv).startsWith("id,createdAt,actorId,actorName,action,targetType,targetId,result,detail\n");
        // 逗号/引号字段按 RFC 4180 包引号并双写引号
        assertThat(csv).contains("\"evil, \"\"name\"\"\"");
        // 公式前缀加单引号防 CSV 注入
        assertThat(csv).contains("'=cmd");
    }

    @Test
    void exportCsv_非管理员拒绝() {
        loginAs("USER");
        assertThatThrownBy(() -> service.exportCsv(
                new AuditQueryService.AuditQuery(null, null, null, null), new StringWriter()))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).selectList(any());
    }
}
