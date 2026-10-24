package com.zhixing.core.memory;

import com.zhixing.dao.entity.AgentMemory;
import com.zhixing.dao.mapper.AgentMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆写入器单测（任务书 §3）：ADD / SUPERSEDE / RETRACT 三动作落库语义。
 */
class MemoryWriterTest {

    private AgentMemoryMapper mapper;

    private MemoryWriter writer;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentMemoryMapper.class);
        writer = new MemoryWriter(mapper);
    }

    @Test
    void add_插入ACTIVE并携带来源会话() {
        writer.apply(List.of(new MemoryOp("ADD", null, "用户偏好深色模式")), 77L);

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(mapper).insert(captor.capture());
        AgentMemory inserted = captor.getValue();
        assertThat(inserted.getId()).isNotNull();
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getContent()).isEqualTo("用户偏好深色模式");
        assertThat(inserted.getSourceSessionId()).isEqualTo(77L);
    }

    @Test
    void supersede_先插新ACTIVE再把旧记录置SUPERSEDED指向新id() {
        writer.apply(List.of(new MemoryOp("SUPERSEDE", 12L, "用户偏好浅色模式（修订）")), 77L);

        ArgumentCaptor<AgentMemory> insertCaptor = ArgumentCaptor.forClass(AgentMemory.class);
        ArgumentCaptor<AgentMemory> updateCaptor = ArgumentCaptor.forClass(AgentMemory.class);
        InOrder order = inOrder(mapper);
        order.verify(mapper).insert(insertCaptor.capture());
        order.verify(mapper).updateById(updateCaptor.capture());

        AgentMemory replacement = insertCaptor.getValue();
        assertThat(replacement.getStatus()).isEqualTo("ACTIVE");
        assertThat(replacement.getContent()).isEqualTo("用户偏好浅色模式（修订）");

        AgentMemory old = updateCaptor.getValue();
        assertThat(old.getId()).isEqualTo(12L);
        assertThat(old.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(old.getSupersededBy()).isEqualTo(replacement.getId());
    }

    @Test
    void retract_置RETRACTED() {
        writer.apply(List.of(new MemoryOp("RETRACT", 13L, null)), null);

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(13L);
        assertThat(captor.getValue().getStatus()).isEqualTo("RETRACTED");
    }

    @Test
    void 单操作失败不阻断后续() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any(AgentMemory.class)))
                .thenThrow(new IllegalStateException("db抖动"));

        // 第一个操作（ADD）insert 抛异常仅告警，第二个操作（RETRACT）照常落库
        writer.apply(List.of(new MemoryOp("ADD", null, "第一条"), new MemoryOp("RETRACT", 13L, null)), 77L);

        verify(mapper).updateById(org.mockito.ArgumentMatchers.any(AgentMemory.class));
    }
}
