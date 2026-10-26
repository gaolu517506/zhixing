package com.zhixing.infra.trace;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.TraceProperties;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Trace 手动埋点（概设 D7）：调用方在业务代码里显式包一层 node(...)，try-finally 语义由本类封装——
 * 不上 AOP/OTel，阶段 4 前埋点即业务逻辑，手动最直白。
 * trace 关闭（zhixing.trace.enabled=false）时 runId 恒为 0，node() 只执行业务体零开销。
 * input/output 统一截断到 zhixing.trace.max-text-length（默认 2000 字符）。
 */
@Slf4j
@Component
public class TraceRecorder {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TraceRunMapper runMapper;

    private final TraceNodeMapper nodeMapper;

    private final TraceProperties props;

    public TraceRecorder(TraceRunMapper runMapper, TraceNodeMapper nodeMapper, TraceProperties props) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.props = props;
    }

    /** 开启一个 run，返回 runId（trace 关闭时返回 0）。 */
    public long startRun(String bizType, Long bizId) {
        return startRun(bizType, bizId, IdUtil.getSnowflakeNextId());
    }

    /**
     * 开启一个 run 并钉死 runId（trace 关闭时返回 0）。AGENT 链路专用：约定
     * trace_run.id == agent_run.id——ReActEngine/ToolExecutor 的节点埋点以 agent runId
     * 为键，不钉死同 id 则节点成孤儿（v1.1 修复的缺口）。
     */
    public long startRun(String bizType, Long bizId, long presetRunId) {
        if (!props.isEnabled()) {
            return 0;
        }
        TraceRun run = new TraceRun();
        run.setId(presetRunId);
        run.setBizType(bizType);
        run.setBizId(bizId);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run.getId();
    }

    public void finishRun(long runId, String status, long totalCostMs, String error) {
        if (runId == 0) {
            return;
        }
        TraceRun patch = new TraceRun();
        patch.setId(runId);
        patch.setStatus(status);
        patch.setTotalCostMs(totalCostMs);
        patch.setErrorMsg(error);
        runMapper.updateById(patch);
    }

    /**
     * 记录一个节点并执行业务体：异常时节点记 FAILED 后原样上抛，业务语义不变。
     * runId=0（trace 关闭）时只执行业务体。
     */
    public <T> T node(long runId, java.util.concurrent.atomic.AtomicInteger seq, String name,
                      Object input, NodeBody<T> body) {
        if (runId == 0) {
            return body.run();
        }
        int nodeSeq = seq.incrementAndGet();
        long start = System.currentTimeMillis();
        String inputText = truncate(toText(input));
        try {
            T output = body.run();
            insertNode(runId, nodeSeq, name, start, inputText, truncate(toText(output)), STATUS_SUCCESS, null);
            return output;
        } catch (RuntimeException e) {
            insertNode(runId, nodeSeq, name, start, inputText, null, STATUS_FAILED, e.getMessage());
            throw e;
        }
    }

    private void insertNode(long runId, int seq, String name, long start, String input, String output,
                            String status, String error) {
        try {
            TraceNode node = new TraceNode();
            node.setId(IdUtil.getSnowflakeNextId());
            node.setRunId(runId);
            node.setName(name);
            node.setSeq(seq);
            node.setCostMs(System.currentTimeMillis() - start);
            node.setInput(input);
            node.setOutput(output);
            node.setStatus(status);
            node.setError(error);
            node.setCreatedAt(LocalDateTime.now());
            nodeMapper.insert(node);
        } catch (Exception e) {
            // 埋点自身失败绝不能影响业务链路：只留日志
            log.warn("trace node [{}] 落库失败: {}", name, e.getMessage());
        }
    }

    private String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= props.getMaxTextLength()) {
            return text;
        }
        return text.substring(0, props.getMaxTextLength()) + "...(truncated)";
    }

    /** 节点业务体。异常一律 RuntimeException（模型客户端异常本就是运行时异常）。 */
    @FunctionalInterface
    public interface NodeBody<T> {
        T run();
    }
}
