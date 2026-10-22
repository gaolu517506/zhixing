package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.service.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent HTTP 入口（概设 §6）：run/confirm 两个 SSE 端点 + cancel + 运行列表。
 * 失败语义 DL-3：HTTP 恒 200 + 业务码；SSE 终态成对规则同 QA。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/run")
    public SseEmitter run(@Valid @RequestBody AgentRunRequest request) {
        log.info("agent run: kbId={}, sessionId={}, input={}",
                request.getKbId(), request.getSessionId(), request.getInput());
        return agentService.run(request);
    }

    /** 挂起恢复：approve 执行 pending 工具 / reject 回填拒绝文案，返回续跑的 SSE。 */
    @PostMapping("/runs/{id}/confirm")
    public SseEmitter confirm(@PathVariable long id, @Valid @RequestBody ConfirmRequest request) {
        log.info("agent confirm: runId={}, approved={}", id, request.approved());
        return agentService.confirm(id, request.approved());
    }

    @PostMapping("/runs/{id}/cancel")
    public Result<Void> cancel(@PathVariable long id) {
        log.info("agent cancel: runId={}", id);
        agentService.cancel(id);
        return Result.ok(null);
    }

    /** 运行列表（前端时间线回放）。 */
    @GetMapping("/runs")
    public Result<List<AgentRunItem>> runs(@RequestParam(required = false) Long sessionId) {
        return Result.ok(agentService.listRuns(sessionId).stream().map(AgentRunItem::of).toList());
    }

    public record ConfirmRequest(@NotNull(message = "approved 不能为空") Boolean approved) {
    }

    public record AgentRunItem(Long id, Long sessionId, Long kbId, String input, String status,
                               Integer iterations, String errorMsg,
                               Integer promptTokens, Integer completionTokens, Long costMs,
                               LocalDateTime createdAt) {

        static AgentRunItem of(AgentRun run) {
            return new AgentRunItem(run.getId(), run.getSessionId(), run.getKbId(), run.getInput(),
                    run.getStatus(), run.getIterations(), run.getErrorMsg(),
                    run.getPromptTokens(), run.getCompletionTokens(), run.getCostMs(),
                    run.getCreatedAt());
        }
    }
}
