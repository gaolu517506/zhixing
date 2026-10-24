package com.zhixing.core.memory;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.config.MemoryProperties;
import com.zhixing.dao.entity.AgentMemory;
import com.zhixing.dao.mapper.AgentMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 长期记忆门面（详设 14-v1.0 §3.4，DL-17 ①②）：回合收尾异步仲裁 + 双链路注入。
 *
 * <p>异步性边界：{@link #arbitrateQuietlyAsync} 本身**绝不抛出**——开关检查、任务提交、
 * 仲裁与落库全段吞异常仅 warn；调用方（QaService/AgentService 回合收尾处）无需任何
 * try/catch，response 也不等待仲裁结果（memory-pool 队列满时任务静默丢弃，增强非必需）。
 *
 * <p>注入块格式：`【已知用户偏好】\n- 内容逐条`，maxInject 条 / 2000 字符上限，
 * 超限取最近更新（updated_at DESC）；无 ACTIVE 记忆或开关关闭返回 null（调用方不追加）。
 */
@Slf4j
@Service
public class LongTermMemoryService {

    private static final int INJECTION_CHAR_LIMIT = 2000;

    private final MemoryArbiter arbiter;

    private final MemoryWriter writer;

    private final AgentMemoryMapper memoryMapper;

    private final MemoryProperties props;

    private final Executor memoryPool;

    public LongTermMemoryService(MemoryArbiter arbiter, MemoryWriter writer,
                                 AgentMemoryMapper memoryMapper, MemoryProperties props,
                                 @Qualifier("memory-pool") Executor memoryPool) {
        this.arbiter = arbiter;
        this.writer = writer;
        this.memoryMapper = memoryMapper;
        this.props = props;
        this.memoryPool = memoryPool;
    }

    /** 回合收尾触发（QaService/AgentService 调用）：异步仲裁本轮 user/assistant 并落库。 */
    public void arbitrateQuietlyAsync(Long sessionId, String userContent, String assistantContent) {
        try {
            if (!props.getLongTerm().isEnabled()
                    || StrUtil.isBlank(userContent) || StrUtil.isBlank(assistantContent)) {
                return;
            }
            memoryPool.execute(() -> {
                try {
                    List<AgentMemory> active = activeMemories();
                    List<Turn> turns = List.of(new Turn("user", userContent), new Turn("assistant", assistantContent));
                    List<MemoryOp> ops = arbiter.arbitrate(active, turns);
                    if (ops.isEmpty()) {
                        return;
                    }
                    writer.apply(ops, sessionId);
                    log.info("长期记忆仲裁完成 sessionId={} ops={}", sessionId,
                            ops.stream().map(MemoryOp::action).toList());
                } catch (Exception e) {
                    log.warn("长期记忆仲裁任务失败（静默放弃）sessionId={}: {}", sessionId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("长期记忆仲裁触发失败（静默放弃）sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /** 注入块；无可用记忆或开关关闭返回 null。 */
    public String injectionBlock() {
        try {
            if (!props.getLongTerm().isEnabled()) {
                return null;
            }
            List<AgentMemory> active = activeMemories();
            if (active.isEmpty()) {
                return null;
            }
            String body = active.stream()
                    .map(m -> "- " + m.getContent())
                    .collect(Collectors.joining("\n"));
            String block = "【已知用户偏好】\n" + body;
            return block.length() <= INJECTION_CHAR_LIMIT
                    ? block
                    : block.substring(0, INJECTION_CHAR_LIMIT);
        } catch (Exception e) {
            log.warn("长期记忆注入块构建失败（按无记忆处理）: {}", e.getMessage());
            return null;
        }
    }

    /** ACTIVE 记忆，最近更新优先（上限 = 注入上限；仲裁与注入共用同一池子）。 */
    private List<AgentMemory> activeMemories() {
        return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getStatus, "ACTIVE")
                .orderByDesc(AgentMemory::getUpdatedAt)
                .last("LIMIT " + Math.max(1, props.getLongTerm().getMaxInject())));
    }
}
