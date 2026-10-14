package com.zhixing.infra.llm;

import com.zhixing.common.ErrorCode;
import com.zhixing.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 远程模型异常 → C0001。放在 infra/llm 而不是 common：
 * common 层不反向依赖 infra，依赖方向保持干净。
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
