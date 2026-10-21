package com.zhixing.controller;

import com.zhixing.common.ErrorCode;
import com.zhixing.common.Result;
import com.zhixing.infra.llm.ModelClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 远程模型异常 → C0001 的协议翻译（包结构治理：@RestControllerAdvice 是 web 接入层组件，
 * 从 infra/llm 归位 controller——controller→infra 为正确的向下依赖，common 依旧不反向依赖 infra）。
 * 对外只暴露错误分类标签，供应商原始报文只留服务端日志（error 级 + 堆栈）。
 */
@Slf4j
@RestControllerAdvice
public class LlmExceptionHandler {

    @ExceptionHandler(ModelClientException.class)
    public Result<Void> handleModelClient(ModelClientException e) {
        log.error("model call failed [type={}, httpStatus={}]", e.getType(), e.getHttpStatus(), e);
        return Result.fail(ErrorCode.REMOTE_MODEL_ERROR, "远程模型调用失败（" + e.getType().getLabel() + "）");
    }
}
