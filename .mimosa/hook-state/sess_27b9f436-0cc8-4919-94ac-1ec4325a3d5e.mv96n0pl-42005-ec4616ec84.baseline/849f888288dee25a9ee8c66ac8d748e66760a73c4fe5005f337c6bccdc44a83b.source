package com.zhixing.common;

/**
 * 业务异常：错误信息的"载体"。Service 层只管抛，GlobalExceptionHandler 负责翻译成 Result。
 *
 * 构造时 customMsg 为空白则回落枚举默认文案——保证 getMessage() 永不为空，
 * 这样全局处理器里调 Result.fail(errorCode, getMessage()) 永远不会踩 DL-2 的雷。
 */
public class BusinessException extends RuntimeException {

    private final transient IErrorCode errorCode;

    public BusinessException(IErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(IErrorCode errorCode, String customMsg) {
        super(customMsg == null || customMsg.isBlank() ? errorCode.message() : customMsg);
        this.errorCode = errorCode;
    }

    public IErrorCode getErrorCode() {
        return errorCode;
    }
}
