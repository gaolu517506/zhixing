package com.zhixing.infra.trace;

import com.zhixing.config.TraceProperties;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * TraceRecorder run 生命周期单测（v1.1）：预设 runId 重载是 AGENT 链路
 * 「trace_run.id == agent_run.id」约定的载体，钉错 id 节点即孤儿（v1.1 修复的缺口）。
 */
class TraceRecorderTest {

    private final TraceRunMapper runMapper = mock(TraceRunMapper.class);
    private final TraceNodeMapper nodeMapper = mock(TraceNodeMapper.class);
    private final TraceProperties props = new TraceProperties();
    private final TraceRecorder recorder = new TraceRecorder(runMapper, nodeMapper, props);

    @Test
    void 预设runId原样落库且返回同值() {
        long runId = recorder.startRun("AGENT", 123L, 456L);

        assertThat(runId).isEqualTo(456L);
        ArgumentCaptor<TraceRun> captor = ArgumentCaptor.forClass(TraceRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(456L);
        assertThat(captor.getValue().getBizType()).isEqualTo("AGENT");
        assertThat(captor.getValue().getBizId()).isEqualTo(123L);
        assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void 未指定runId时自动生成雪花id() {
        long runId = recorder.startRun("QA", 1L);

        ArgumentCaptor<TraceRun> captor = ArgumentCaptor.forClass(TraceRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(runId).isEqualTo(captor.getValue().getId());
        assertThat(runId).isPositive();
    }

    @Test
    void trace关闭时返回0且不落库() {
        props.setEnabled(false);

        assertThat(recorder.startRun("AGENT", 1L, 2L)).isZero();
        verifyNoInteractions(runMapper, nodeMapper);
    }
}
