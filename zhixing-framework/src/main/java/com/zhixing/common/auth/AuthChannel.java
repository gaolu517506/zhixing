package com.zhixing.common.auth;

/**
 * 认证渠道（D16）：同一权限模型下的两种进入方式。
 */
public enum AuthChannel {
    /** Web 控制台会话（auth_session opaque token）。 */
    WEB,
    /** 开放 API token（api_token，主体=归属用户 + API 渠道标记）。 */
    API
}
