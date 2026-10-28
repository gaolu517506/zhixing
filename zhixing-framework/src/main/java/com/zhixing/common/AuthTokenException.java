package com.zhixing.common;

/**
 * 认证 token 专属异常（T6-0 契约基线）：命中但不可用与未命中是两种语义，
 * Filter 层分别翻译（配额超限 A0007 / 未命中 401）。
 */
public class AuthTokenException extends RuntimeException {

    public AuthTokenException(IErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    private final IErrorCode errorCode;

    public IErrorCode errorCode() {
        return errorCode;
    }
}
