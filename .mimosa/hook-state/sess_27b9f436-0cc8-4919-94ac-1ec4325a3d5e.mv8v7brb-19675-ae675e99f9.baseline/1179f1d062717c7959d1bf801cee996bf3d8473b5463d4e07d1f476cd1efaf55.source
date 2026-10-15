package com.zhixing.common;

public enum ErrorCode implements IErrorCode {
    // ========== A 类错误：客户端错误 ==========
    CLIENT_ERROR("A0000", "客户端参数错误"),

    // ========== B 类错误：服务内部错误 ==========
    SERVER_ERROR("B0001", "服务内部错误"),

    // ========== C 类错误：远程模型错误 ==========
    REMOTE_MODEL_ERROR("C0001", "远程模型调用失败");

    /**
     * 错误码
     */
    private final String code;

    /**
     * 错误信息
     */
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
