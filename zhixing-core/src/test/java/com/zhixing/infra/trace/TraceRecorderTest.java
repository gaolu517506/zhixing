package com.zhixing.infra.trace;

import com.zhixing.config.TraceProperties;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * TraceRecorder run 生命周期单测（v1.1）：预设 runId 重载是 AGENT 链路
 * 「trace_run.id == agent_run.id」约定的载体，钉错 id 节点即孤儿（v1.1 修复的缺口）。
 * openContext/currentSeq（修复④）：confirm 续跑段的计数器从已落节点 max(seq) 续起，
 * 同一 run 的 seq 全程严格递增，不再各段从 1 重复。
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

    // ===== openContext / currentSeq（v1.1 修复④）=====

    @Test
    void openContext计数器从已落节点max续起() {
        doReturn(List.of(7)).when(nodeMapper).selectObjs(any());

        TraceContext ctx = recorder.openContext(77L);

        assertThat(ctx.runId()).isEqualTo(77L);
        assertThat(ctx.seq().get()).isEqualTo(7);
        assertThat(ctx.nextSeq()).isEqualTo(8);
    }

    @Test
    void openContext无节点或trace关闭时从0起() {
        doReturn(List.of()).when(nodeMapper).selectObjs(any());
        assertThat(recorder.openContext(77L).nextSeq()).isEqualTo(1);

        // trace 关闭 → runId=0 的直通上下文
        assertThat(recorder.openContext(0L).runId()).isZero();
        assertThat(recorder.openContext(0L).nextSeq()).isEqualTo(1);
    }

    @Test
    void currentSeq查询失败降级为0不影响业务链路() {
        doThrow(new RuntimeException("pg down")).when(nodeMapper).selectObjs(any());

        assertThat(recorder.currentSeq(77L)).isZero();
        assertThat(recorder.openContext(77L).nextSeq()).isEqualTo(1);
    }
}
