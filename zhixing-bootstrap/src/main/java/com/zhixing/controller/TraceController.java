package com.zhixing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.Result;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.TraceRunMapper;
import com.zhixing.service.TraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trace 观测查询（概设 §6）：run 倒序分页列表 + run 详情（nodes 按 seq 全量）。
 * 只读；列表分页用 LambdaQueryWrapper + LIMIT/OFFSET（数据量小，不引分页拦截器）。
 * 详情的归属权限在 TraceService 入口判定（02 §B2：本人或系统 ADMIN）。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TraceRunMapper runMapper;

    private final TraceService traceService;

    public TraceController(TraceRunMapper runMapper, TraceService traceService) {
        this.runMapper = runMapper;
        this.traceService = traceService;
    }

    @GetMapping("/runs")
    public Result<TraceRunPage> listRuns(@RequestParam(required = false) String bizType,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<TraceService.TraceRunItem> items = runMapper.selectList(wrapperOf(bizType)
                        .orderByDesc(TraceRun::getId)
                        .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize))
                .stream().map(TraceService.TraceRunItem::of).toList();
        long total = runMapper.selectCount(wrapperOf(bizType));
        return Result.ok(new TraceRunPage(items, total, safePage, safeSize));
    }

    @GetMapping("/runs/{id}")
    public Result<TraceService.TraceRunDetail> getRun(@PathVariable Long id) {
        return Result.ok(traceService.getRunDetail(id));
    }

    private static LambdaQueryWrapper<TraceRun> wrapperOf(String bizType) {
        return new LambdaQueryWrapper<TraceRun>()
                .eq(bizType != null && !bizType.isBlank(), TraceRun::getBizType, bizType);
    }

    public record TraceRunPage(List<TraceService.TraceRunItem> items, long total, int page, int size) {
    }
}
