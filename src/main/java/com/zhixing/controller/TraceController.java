package com.zhixing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.Result;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trace 观测查询（概设 §6）：run 倒序分页列表 + run 详情（nodes 按 seq 全量）。
 * 只读；列表分页用 LambdaQueryWrapper + LIMIT/OFFSET（数据量小，不引分页拦截器）。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    /** 详情页 input/output 展示截断长度（落库上限 2000，前端只需预览）。 */
    private static final int NODE_TEXT_PREVIEW = 200;

    private static final String TRUNCATED_SUFFIX = "…(truncated)";

    private static final int MAX_PAGE_SIZE = 100;

    private final TraceRunMapper runMapper;

    private final TraceNodeMapper nodeMapper;

    public TraceController(TraceRunMapper runMapper, TraceNodeMapper nodeMapper) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
    }

    @GetMapping("/runs")
    public Result<TraceRunPage> listRuns(@RequestParam(required = false) String bizType,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<TraceRunItem> items = runMapper.selectList(wrapperOf(bizType)
                        .orderByDesc(TraceRun::getId)
                        .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize))
                .stream().map(TraceRunItem::of).toList();
        long total = runMapper.selectCount(wrapperOf(bizType));
        return Result.ok(new TraceRunPage(items, total, safePage, safeSize));
    }

    @GetMapping("/runs/{id}")
    public Result<TraceRunDetail> getRun(@PathVariable Long id) {
        TraceRun run = runMapper.selectById(id);
        if (run == null) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "Trace run 不存在：" + id);
        }
        List<TraceNodeItem> nodes = nodeMapper.selectList(new LambdaQueryWrapper<TraceNode>()
                        .eq(TraceNode::getRunId, id)
                        .orderByAsc(TraceNode::getSeq))
                .stream().map(TraceNodeItem::of).toList();
        return Result.ok(new TraceRunDetail(TraceRunItem.of(run), nodes));
    }

    private static LambdaQueryWrapper<TraceRun> wrapperOf(String bizType) {
        return new LambdaQueryWrapper<TraceRun>()
                .eq(bizType != null && !bizType.isBlank(), TraceRun::getBizType, bizType);
    }

    public record TraceRunItem(Long id, String bizType, Long bizId, String status,
                               Long totalCostMs, String errorMsg, LocalDateTime createdAt) {

        static TraceRunItem of(TraceRun run) {
            return new TraceRunItem(run.getId(), run.getBizType(), run.getBizId(), run.getStatus(),
                    run.getTotalCostMs(), run.getErrorMsg(), run.getCreatedAt());
        }
    }

    public record TraceNodeItem(String name, Integer seq, Long costMs, String status,
                                String error, String input, String output) {

        static TraceNodeItem of(TraceNode node) {
            return new TraceNodeItem(node.getName(), node.getSeq(), node.getCostMs(), node.getStatus(),
                    node.getError(), preview(node.getInput()), preview(node.getOutput()));
        }
    }

    public record TraceRunDetail(TraceRunItem run, List<TraceNodeItem> nodes) {
    }

    public record TraceRunPage(List<TraceRunItem> items, long total, int page, int size) {
    }

    private static String preview(String text) {
        if (text == null || text.length() <= NODE_TEXT_PREVIEW) {
            return text;
        }
        return text.substring(0, NODE_TEXT_PREVIEW) + TRUNCATED_SUFFIX;
    }
}
