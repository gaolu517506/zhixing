package com.zhixing.core.memory;

import cn.hutool.core.util.IdUtil;
import com.zhixing.dao.entity.AgentMemory;
import com.zhixing.dao.mapper.AgentMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆写入器（详设 14-v1.0 §3.3）：三动作落库。
 * ADD 插 ACTIVE；SUPERSEDE 插新 ACTIVE 后旧记录置 SUPERSEDED+superseded_by；
 * RETRACT 置 RETRACTED。单操作失败只告警不阻断后续操作（记忆是增强非必需）。
 */
@Slf4j
@Component
public class MemoryWriter {

    private final AgentMemoryMapper mapper;

    public MemoryWriter(AgentMemoryMapper mapper) {
        this.mapper = mapper;
    }

    public void apply(List<MemoryOp> ops, Long sourceSessionId) {
        for (MemoryOp op : ops) {
            try {
                switch (op.action()) {
                    case MemoryOp.ADD -> insertActive(op.content(), sourceSessionId);
                    case MemoryOp.SUPERSEDE -> supersede(op.targetId(), op.content(), sourceSessionId);
                    case MemoryOp.RETRACT -> retract(op.targetId());
                    default -> log.warn("未知记忆操作，跳过: {}", op.action());
                }
            } catch (Exception e) {
                log.warn("记忆操作落库失败 action={} targetId={}: {}", op.action(), op.targetId(), e.getMessage());
            }
        }
    }

    private void insertActive(String content, Long sourceSessionId) {
        AgentMemory memory = new AgentMemory();
        memory.setId(IdUtil.getSnowflakeNextId());
        memory.setContent(content);
        memory.setStatus("ACTIVE");
        memory.setSourceSessionId(sourceSessionId);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        mapper.insert(memory);
    }

    /** 先插新 ACTIVE（拿到 id），再把旧记录置 SUPERSEDED 并指向新 id——查询方永远能看到链。 */
    private void supersede(Long targetId, String newContent, Long sourceSessionId) {
        AgentMemory replacement = new AgentMemory();
        replacement.setId(IdUtil.getSnowflakeNextId());
        replacement.setContent(newContent);
        replacement.setStatus("ACTIVE");
        replacement.setSourceSessionId(sourceSessionId);
        replacement.setCreatedAt(LocalDateTime.now());
        replacement.setUpdatedAt(LocalDateTime.now());
        mapper.insert(replacement);

        AgentMemory patch = new AgentMemory();
        patch.setId(targetId);
        patch.setStatus("SUPERSEDED");
        patch.setSupersededBy(replacement.getId());
        patch.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(patch);
    }

    private void retract(Long targetId) {
        AgentMemory patch = new AgentMemory();
        patch.setId(targetId);
        patch.setStatus("RETRACTED");
        patch.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(patch);
    }
}
