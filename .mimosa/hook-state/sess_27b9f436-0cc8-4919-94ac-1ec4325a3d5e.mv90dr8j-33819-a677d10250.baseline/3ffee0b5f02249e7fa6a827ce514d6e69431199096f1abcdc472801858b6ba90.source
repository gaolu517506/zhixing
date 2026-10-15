package com.zhixing.common;

import lombok.Getter;

/**
 * 统一响应体：全项目唯一的对外协议。code="0" 表示成功，其余为业务错误码（见 ErrorCode）。
 * 构造器私有——所有 Result 必须经静态工厂创建，成功码与默认文案只允许出现在这一处。
 */
@Getter
public class Result<T> {

    public static final String SUCCESS_CODE = "0";
    public static final String SUCCESS_MESSAGE = "success";

    private final String code;
    private final String message;
    private final T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static Result<Void> fail(IErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.message(), null);
    }

    /**
     * DL-2 决策：customMsg 为 null/空白时直接抛 IllegalArgumentException——
     * 自定义文案必须有内容，让问题在开发期炸出来，而不是让前端收到一条空错误提示。
     */
    public static Result<Void> fail(IErrorCode errorCode, String customMsg) {
        if (customMsg == null || customMsg.isBlank()) {
            throw new IllegalArgumentException(
                    "Result.fail 的 customMsg 不能为空，错误码：" + errorCode.code());
        }
        return new Result<>(errorCode.code(), customMsg, null);
    }
}
