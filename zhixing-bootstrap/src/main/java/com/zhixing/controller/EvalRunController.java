package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.EvalRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 验收向导 HTTP 入口（02-技术方案 §B6，F6）：发起评估（requireKbAdmin 在 service 入口）→
 * 轮询状态与报告（含 delta 与语料规模诚实标注）。
 */
@Slf4j
@Validated
@RestController
public class EvalRunController {

    private final EvalRunService evalRunService;

    public EvalRunController(EvalRunService evalRunService) {
        this.evalRunService = evalRunService;
    }

    @PostMapping("/api/eval/runs")
    public Result<EvalRunService.EvalRunView> start(@Valid @RequestBody StartEvalRunRequest request) {
        log.info("eval run start: kbId={}, setPath={}, inline={}",
                request.kbId(), request.setPath(), request.setJson() != null);
        return Result.ok(evalRunService.start(
                new EvalRunService.StartRunRequest(request.kbId(), request.setPath(), request.setJson())));
    }

    @GetMapping("/api/eval/runs/{id}")
    public Result<EvalRunService.EvalRunView> get(@PathVariable long id) {
        return Result.ok(evalRunService.get(id));
    }

    @GetMapping("/api/eval/runs")
    public Result<List<EvalRunService.EvalRunView>> list(@RequestParam Long kbId) {
        return Result.ok(evalRunService.list(kbId));
    }

    public record StartEvalRunRequest(@NotNull(message = "kbId 不能为空") Long kbId,
                                      String setPath,
                                      String setJson) {
    }
}
