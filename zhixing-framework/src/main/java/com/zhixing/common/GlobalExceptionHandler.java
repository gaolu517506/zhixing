package com.zhixing.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常翻译官：把所有异常统一翻译成 Result。
 * DL-3 决策：HTTP 状态码恒为 200，错误语义全在业务码里——前端只写一套"解析 code"的逻辑。
 *
 * <p>v2.0 认证段例外（02 §B1-B3）：A0005/A0006/A0007 额外映射真实 HTTP 状态
 * （401/403/429）——与 AuthFilter 的 401 对齐，安全语义对工具链（网关/巡检/矩阵测试）
 * 可见；body 仍是 Result 协议，前端解析逻辑不变。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的失败，warn 级别足够，不打堆栈。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("business error [{}]: {}", e.getErrorCode().code(), e.getMessage());
        return ResponseEntity.status(httpStatusOf(e.getErrorCode().code()))
                .body(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    private static HttpStatus httpStatusOf(String code) {
        return switch (code) {
            case "A0005" -> HttpStatus.UNAUTHORIZED;
            case "A0006" -> HttpStatus.FORBIDDEN;
            case "A0007" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.OK;
        };
    }

    /** 参数校验失败：取第一个字段错误给前端（A0000）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse(ErrorCode.CLIENT_ERROR.message());
        log.warn("validation failed: {}", msg);
        return Result.fail(ErrorCode.CLIENT_ERROR, msg);
    }

    /** 参数类型不匹配（如 ?times=abc 传给 Integer）：客户端的错，归 A0000，不值得打堆栈。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知";
        log.warn("type mismatch on param [{}]: {}", e.getName(), e.getMessage());
        return Result.fail(ErrorCode.CLIENT_ERROR, "参数 " + e.getName() + " 类型不正确，期望 " + expected);
    }

    /** 请求体不可读（JSON 格式错误、缺 body）：客户端的错。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body: {}", e.getMessage());
        return Result.fail(ErrorCode.CLIENT_ERROR, "请求体格式错误");
    }

    /** 必填参数缺失：客户端的错。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("missing required param [{}]", e.getParameterName());
        return Result.fail(ErrorCode.CLIENT_ERROR, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 未知路径 404（无 handler / 静态资源 miss）：客户端的错，归 A 段——
     * 评审 S3：曾落兜底 handleUnexpected 变 B0001「服务内部错误」+ error 堆栈，语义失实。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public Result<Void> handleNotFound(Exception e) {
        log.warn("resource not found: {}", e.getMessage());
        return Result.fail(ErrorCode.NOT_FOUND, "资源不存在");
    }

    /** 兜底：真实原因只留服务端日志（error 级 + 堆栈），对外永远只说"服务内部错误"。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("uncaught exception", e);
        return Result.fail(ErrorCode.SERVER_ERROR);
    }
}
