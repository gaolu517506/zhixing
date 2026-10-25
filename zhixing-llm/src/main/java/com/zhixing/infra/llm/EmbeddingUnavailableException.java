package com.zhixing.infra.llm;

import lombok.Getter;

/**
 * embedding 主备全部不可用（v1.1-B T5-2，DL-19）。
 * 继承 ModelClientException 复用 LlmExceptionHandler 的 C0001 映射与 IngestionPipeline
 * 的 FAILED 状态机——调用方零感知新异常类型。message 必含主备双方失败摘要，原始堆栈以
 * primary 异常为根因，fallback 异常经 {@link #getFallbackCause()} 保留。
 */
@Getter
public class EmbeddingUnavailableException extends ModelClientException {

    private final Throwable fallbackCause;

    public EmbeddingUnavailableException(String message, Throwable primaryCause, Throwable fallbackCause) {
        super(ErrorType.SERVER_ERROR, message, primaryCause);
        this.fallbackCause = fallbackCause;
    }
}
