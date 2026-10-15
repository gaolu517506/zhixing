package com.zhixing.infra.llm;

/**
 * 远程模型错误分类。这个枚举是阶段 4 降级决策的直接输入：
 * fallbackCapable=true 才值得切换候选供应商重试，false 的（如鉴权失败）切了也没用。
 */
public enum ErrorType {

    UNAUTHORIZED(false, "鉴权失败"),
    RATE_LIMITED(false, "触发限流"),
    SERVER_ERROR(true, "供应商服务端错误"),
    CLIENT_ERROR(false, "请求被供应商拒绝"),
    NETWORK_ERROR(true, "网络异常"),
    TIMEOUT(true, "超时"),
    INVALID_RESPONSE(false, "响应结构异常");

    private final boolean fallbackCapable;
    private final String label;

    ErrorType(boolean fallbackCapable, String label) {
        this.fallbackCapable = fallbackCapable;
        this.label = label;
    }

    /** true = 切换 fallback 候选重试有意义（NETWORK/SERVER/TIMEOUT）；false = 换供应商也解决不了。 */
    public boolean isFallbackCapable() {
        return fallbackCapable;
    }

    /** 面向用户的短标签（不含供应商原始报文，防泄内部信息）。 */
    public String getLabel() {
        return label;
    }

    /** HTTP 状态码 → 错误类型。非 4xx/5xx 的意外状态一律按结构异常处理。 */
    public static ErrorType fromHttpStatus(int status) {
        if (status == 401 || status == 403) {
            return UNAUTHORIZED;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        if (status >= 400) {
            return CLIENT_ERROR;
        }
        return INVALID_RESPONSE;
    }
}
