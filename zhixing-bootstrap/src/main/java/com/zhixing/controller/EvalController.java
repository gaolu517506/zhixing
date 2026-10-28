package com.zhixing.controller;

import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.Result;
import com.zhixing.config.EvalProperties;
import com.zhixing.service.EvalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;

/**
 * 评测旁路 HTTP 入口（详设 §10.2，决策 D11）：直接调 RetrievalEngine 返回中间产物，
 * 两通道结果分开返回——这是"裁决关键词通道去留"（D6）的数据来源。
 * zhixing.eval.enabled=false（默认）时整个 Controller 不注册，接口真 404，生产零开销。
 * X-Eval-Key 防误用：评测是调优动作，不该被业务前端顺手调用；知识库内容权限在
 * EvalService 入口判定（02 §B2）——两道门语义不同，缺一不可。
 */
@Slf4j
@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixing.eval", name = "enabled", havingValue = "true")
public class EvalController {

    private final EvalService evalService;

    private final EvalProperties evalProps;

    public EvalController(EvalService evalService, EvalProperties evalProps) {
        this.evalService = evalService;
        this.evalProps = evalProps;
    }

    @PostMapping("/api/eval/retrieve")
    public Result<EvalService.EvalRetrieveView> retrieve(
            @RequestHeader(value = "X-Eval-Key", required = false) String evalKey,
            @Valid @RequestBody EvalRequest request) {
        checkKey(evalKey);
        return Result.ok(evalService.retrieve(new EvalService.EvalRetrieveRequest(
                request.kbId(), request.question(), request.topK(),
                request.keywords(), request.history())));
    }

    /** 请求体沿用既有契约（字段与 EvalService.EvalRetrieveRequest 一致，controller 侧校验注解）。 */
    public record EvalRequest(@NotNull Long kbId, @NotBlank String question, Integer topK,
                              java.util.List<com.zhixing.core.memory.Turn> history,
                              java.util.List<String> keywords) {
    }

    private void checkKey(String evalKey) {
        if (evalKey == null || evalKey.isBlank()
                || !MessageDigest.isEqual(evalKey.getBytes(), evalProps.getKey().getBytes())) {
            throw new BusinessException(ErrorCode.EVAL_KEY_INVALID);
        }
    }
}
