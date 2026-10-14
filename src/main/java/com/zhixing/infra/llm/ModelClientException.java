package com.zhixing.infra.llm;

import lombok.Getter;

/**
 * 远程模型调用异常。type 是阶段 4 降级决策的输入；
 * httpStatus 记录供应商响应状态，非 HTTP 路径（网络 IO / 解析失败）为 -1。
 */
@Getter
public class ModelClientException extends RuntimeException {

    private final ErrorType type;

    private final int httpStatus;

    public ModelClientException(ErrorType type, String message) {
        this(type, message, -1, null);
    }

    public ModelClientException(ErrorType type, String message, Throwable cause) {
        this(type, message, -1, cause);
    }

    public ModelClientException(ErrorType type, String message, int httpStatus) {
        this(type, message, httpStatus, null);
    }

    private ModelClientException(ErrorType type, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.httpStatus = httpStatus;
    }

    /** 按 HTTP 状态分类构造，message 由调用方给（对外安全文案），原始 body 只进日志。 */
    public static ModelClientException fromHttpStatus(int status, String message) {
        return new ModelClientException(ErrorType.fromHttpStatus(status), message, status);
    }
}
