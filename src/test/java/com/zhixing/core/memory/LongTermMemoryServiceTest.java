package com.zhixing.core.memory;

import com.zhixing.config.MemoryProperties;
import com.zhixing.dao.entity.AgentMemory;
import com.zhixing.dao.mapper.AgentMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆门面单测（任务书 §3）：注入块拼装/截断/无记忆 null/开关关闭；触发开关关闭零调用、
 * 仲裁异常静默、池拒绝静默。关键红线：门面自身绝不抛出（QaService/AgentService 零防御依赖它）。
 */
class LongTermMemoryServiceTest {

    private MemoryArbiter arbiter;

    private MemoryWriter writer;

    private AgentMemoryMapper memoryMapper;

    private MemoryProperties props;

    private Executor memoryPool;

    private LongTermMemoryService service;

    private static AgentMemory mem(long id, String content) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setContent(content);
        m.setStatus("ACTIVE");
        return m;
    }

    @BeforeEach
    void setUp() {
        arbiter = mock(MemoryArbiter.class);
        writer = mock(MemoryWriter.class);
        memoryMapper = mock(AgentMemoryMapper.class);
        props = new MemoryProperties();
        memoryPool = Runnable::run;   // 同步直跑：任务体在当前线程执行，便于断言
        service = new LongTermMemoryService(arbiter, writer, memoryMapper, props, memoryPool);
    }

    // ===== 注入 =====

    @Test
    void 注入块_逐条拼装() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(mem(1L, "偏好A"), mem(2L, "偏好B")));

        assertThat(service.injectionBlock()).isEqualTo("【已知用户偏好】\n- 偏好A\n- 偏好B");
    }

    @Test
    void 注入块_超2000字符截断() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(mem(1L, "长".repeat(3000))));

        String block = service.injectionBlock();
        assertThat(block).isNotNull().hasSize(2000);
    }

    @Test
    void 无ACTIVE记忆_返回null() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.injectionBlock()).isNull();
    }

    @Test
    void 开关关闭_注入返回null且零查询() {
        props.getLongTerm().setEnabled(false);

        assertThat(service.injectionBlock()).isNull();
        verify(memoryMapper, never()).selectList(any());
    }

    // ===== 触发 =====

    @Test
    void 触发_异步任务内完成仲裁与落库() {
        props.getLongTerm().setEnabled(true);
        when(memoryMapper.selectList(any())).thenReturn(List.of(mem(12L, "旧偏好")));
        when(arbiter.arbitrate(anyList(), anyList())).thenReturn(
                List.of(new MemoryOp("ADD", null, "新事实")));

        service.arbitrateQuietlyAsync(77L, "问题", "回答");

        verify(arbiter).arbitrate(anyList(), anyList());
        verify(writer).apply(org.mockito.ArgumentMatchers.argThat(
                        ops -> ops.size() == 1 && "ADD".equals(ops.get(0).action())),
                org.mockito.ArgumentMatchers.eq(77L));
    }

    @Test
    void 触发_开关关闭_零提交零查询() {
        props.getLongTerm().setEnabled(false);
        Executor neverPool = task -> {
            throw new AssertionError("开关关闭不应提交任务");
        };
        LongTermMemoryService guarded = new LongTermMemoryService(arbiter, writer, memoryMapper, props, neverPool);

        guarded.arbitrateQuietlyAsync(77L, "问题", "回答");

        verify(arbiter, never()).arbitrate(anyList(), anyList());
    }

    @Test
    void 触发_仲裁任务内异常_静默不外泄() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(arbiter.arbitrate(anyList(), anyList())).thenThrow(new IllegalStateException("llm down"));

        assertThatCode(() -> service.arbitrateQuietlyAsync(77L, "问题", "回答"))
                .doesNotThrowAnyException();
        verify(writer, never()).apply(anyList(), any());
    }

    @Test
    void 触发_池拒绝提交_静默不外泄() {
        Executor rejectingPool = task -> {
            throw new IllegalStateException("queue full");
        };
        LongTermMemoryService rejecting = new LongTermMemoryService(arbiter, writer, memoryMapper, props, rejectingPool);

        assertThatCode(() -> rejecting.arbitrateQuietlyAsync(77L, "问题", "回答"))
                .doesNotThrowAnyException();
        verify(arbiter, never()).arbitrate(anyList(), anyList());
    }
}
