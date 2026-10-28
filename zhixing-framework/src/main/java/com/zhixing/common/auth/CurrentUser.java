package com.zhixing.common.auth;

/**
 * 当前请求的认证主体（T6-0 契约基线）。纯值对象，不携带任何 Web 依赖——
 * 由 bootstrap 的 AuthFilter 解析 token 后注入 {@link AuthContext}，core 层只读。
 *
 * @param id          用户 ID（雪花）
 * @param username    用户名（审计展示用）
 * @param systemRole  系统角色：ADMIN / USER
 * @param channel     认证渠道（WEB / API）
 */
public record CurrentUser(Long id, String username, String systemRole, AuthChannel channel) {

    private static final String ROLE_ADMIN = "ADMIN";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(systemRole);
    }
}
