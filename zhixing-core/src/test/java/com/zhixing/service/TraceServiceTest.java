package com.zhixing.service;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Trace 详情归属判定单测（02 §B2）：本人或系统 ADMIN 可见；非归属人 A0006
 * （不泄露他人 run 存在性）；run 不存在维持既有 A0000 文案。
 */
class TraceServiceTest {

    private TraceRunMapper runMapper;

    private TraceNodeMapper nodeMapper;

    private QaSessionMapper sessionMapper;

    private TraceService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(TraceRunMapper.class);
        nodeMapper = mock(TraceNodeMapper.class);
        sessionMapper = mock(QaSessionMapper.class);
        service = new TraceService(runMapper, nodeMapper, sessionMapper);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private TraceRun run(long id, long bizId) {
        TraceRun run = new TraceRun();
        run.setId(id);
        run.setBizType("QA");
        run.setBizId(bizId);
        run.setStatus("COMPLETED");
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    private QaSession session(Long ownerId) {
        QaSession session = new QaSession();
        session.setId(77L);
        session.setUserId(ownerId);
        return session;
    }

    private void loginAs(long userId, String role) {
        AuthContext.set(new CurrentUser(userId, "u" + userId, role, AuthChannel.WEB));
    }

    @Test
    void 详情_归属本人可见() {
        loginAs(42L, "USER");
        when(runMapper.selectById(5L)).thenReturn(run(5L, 77L));
        when(sessionMapper.selectById(77L)).thenReturn(session(42L));
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.getRunDetail(5L).run().id()).isEqualTo(5L);
    }

    @Test
    void 详情_系统管理员可见任意归属() {
        loginAs(1L, "ADMIN");
        when(runMapper.selectById(5L)).thenReturn(run(5L, 77L));
        when(nodeMapper.selectList(any())).thenReturn(List.of(new TraceNode()));

        assertThat(service.getRunDetail(5L)).isNotNull();
    }

    @Test
    void 详情_非归属人A0006() {
        loginAs(43L, "USER");
        when(runMapper.selectById(5L)).thenReturn(run(5L, 77L));
        when(sessionMapper.selectById(77L)).thenReturn(session(42L));

        assertThatThrownBy(() -> service.getRunDetail(5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void 详情_会话缺失或无归属时非管理员A0006() {
        loginAs(43L, "USER");
        when(runMapper.selectById(5L)).thenReturn(run(5L, 77L));
        when(sessionMapper.selectById(77L)).thenReturn(null);
        assertThatThrownBy(() -> service.getRunDetail(5L)).isInstanceOf(BusinessException.class);

        // 存量无主会话（user_id 为 null）同样收敛为拒绝
        when(sessionMapper.selectById(77L)).thenReturn(session(null));
        assertThatThrownBy(() -> service.getRunDetail(5L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 详情_不存在维持A0000文案() {
        loginAs(42L, "USER");
        when(runMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getRunDetail(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Trace run 不存在");
    }
}
