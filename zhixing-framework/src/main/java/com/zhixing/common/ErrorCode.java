package com.zhixing.common;

public enum ErrorCode implements IErrorCode {
    // ========== A 类错误：客户端错误 ==========
    CLIENT_ERROR("A0000", "客户端参数错误"),
    NOT_FOUND("A0004", "资源不存在"),
    // v2.0 认证/权限段（契约基线 T6-0 预分配：A0008-A0099 备用，追加保持分段）
    UNAUTHORIZED("A0005", "未认证"),
    FORBIDDEN("A0006", "无权限访问"),
    QUOTA_EXCEEDED("A0007", "配额超限"),

    // ========== B 类错误：服务内部错误 ==========
    SERVER_ERROR("B0001", "服务内部错误"),

    // ========== C 类错误：远程模型错误 ==========
    REMOTE_MODEL_ERROR("C0001", "远程模型调用失败"),

    // ========== K 类错误：知识库（契约基线预分配给入库线 T1-3/T1-4；K1008-K1099 备用，追加保持分段） ==========
    KB_NOT_FOUND("K1001", "知识库不存在"),
    DOC_NOT_FOUND("K1002", "文档不存在"),
    UPLOAD_FILE_INVALID("K1003", "上传文件不合法"),
    FILE_TYPE_UNSUPPORTED("K1004", "不支持的文件类型"),
    FILE_STORE_FAILED("K1005", "文件保存失败"),
    DOC_STATE_INVALID("K1006", "文档当前状态不允许该操作"),
    FILE_READ_FAILED("K1007", "文件读取失败"),

    // ========== Q 类错误：问答（契约基线预分配给检索问答线 T1-5~T1-7；Q2005-Q2099 备用，追加保持分段） ==========
    SESSION_NOT_FOUND("Q2001", "会话不存在"),
    MESSAGE_NOT_FOUND("Q2002", "消息不存在"),
    EVAL_KEY_INVALID("Q2003", "评测密钥无效");

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
