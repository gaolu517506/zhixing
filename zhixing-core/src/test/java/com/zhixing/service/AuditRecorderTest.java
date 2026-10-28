package com.zhixing.service;

import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.mapper.AuditEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AuditRecorder 契约测试（02 §B3）：字段完整落库；落库失败只 warn 绝不抛——
 * 审计不允许阻塞主链路（任务书验收③）。
 */
class AuditRecorderTest {

    private AuditEventMapper mapper;

    private AuditRecorderImpl recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditEventMapper.class);
        recorder = new AuditRecorderImpl(mapper);
    }

    @Test
    void record_字段完整落库() {
        recorder.record(7L, "alice", "DOC_UPLOAD", "DOCUMENT", "123", "OK", "{\"kbId\":9}");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mapper).insert(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getId()).isNotNull();
        assertThat(event.getActorId()).isEqualTo(7L);
        assertThat(event.getActorName()).isEqualTo("alice");
        assertThat(event.getAction()).isEqualTo("DOC_UPLOAD");
        assertThat(event.getTargetType()).isEqualTo("DOCUMENT");
        assertThat(event.getTargetId()).isEqualTo("123");
        assertThat(event.getResult()).isEqualTo("OK");
        assertThat(event.getDetail()).isEqualTo("{\"kbId\":9}");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void record_落库异常不抛_主链路无感知() {
        doThrow(new IllegalStateException("db down")).when(mapper).insert(any(AuditEvent.class));

        assertThatCode(() -> recorder.record(1L, "bob", "KB_DELETE", "KB", "9", "OK", null))
                .doesNotThrowAnyException();
    }

    @Test
    void record_系统动作actor可空() {
        recorder.record(null, null, "LOGIN", "USER", "ghost", "FAIL", "{\"reason\":\"BAD_CREDENTIALS\"}");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getActorId()).isNull();
        assertThat(captor.getValue().getActorName()).isNull();
        assertThat(captor.getValue().getTargetId()).isEqualTo("ghost");
        assertThat(captor.getValue().getResult()).isEqualTo("FAIL");
    }
}
