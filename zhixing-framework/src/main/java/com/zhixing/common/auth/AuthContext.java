package com.zhixing.common.auth;

/**
 * 请求级认证上下文（ThreadLocal，T6-0 契约基线，D13）。
 *
 * <p>生命周期契约：bootstrap 的 AuthFilter 在请求进入时 {@link #set}，finally 中
 * {@link #clear()}——线程池复用下漏清会把上一个请求的身份泄漏给下一个请求，
 * 实现方必须用 try-finally 包住整个请求处理。
 *
 * <p>未认证请求（白名单路径除外）在 Filter 层即被拒绝，业务代码可以假定
 * {@link #current()} 非空；仍提供 {@link #currentOrNull()} 供审计等可选场景判空。
 */
public final class AuthContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser currentOrNull() {
        return HOLDER.get();
    }

    public static CurrentUser current() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("AuthContext 为空：未认证请求不应到达业务层（Filter 白名单配置有误？）");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
