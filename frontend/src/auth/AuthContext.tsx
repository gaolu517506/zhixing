import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { clearToken, getToken, setToken, UNAUTHORIZED_EVENT } from "../lib/auth";
import * as authApi from "../lib/authApi";
import type { AuthUser } from "../lib/types";

/**
 * 会话上下文（任务书 29-§1③）：挂载时按 token 拉取当前用户；任意请求 401 广播的
 * UNAUTHORIZED_EVENT 在此消费——清会话置空 user，守卫据此重定向 /login（带 from）。
 * login/setup 成功后调用 refresh() 以登录响应直接建立会话（避免二次 me 往返）。
 */
interface AuthState {
  user: AuthUser | null;
  /** 会话校验进行中（守卫据此刻画加载态，避免误跳登录）。 */
  loading: boolean;
  refresh: () => Promise<void>;
  /** 登录/首启 setup 成功：写 token + 立即建立会话。 */
  signIn: (token: string, user: AuthUser) => void;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState<boolean>(Boolean(getToken()));

  const refresh = useCallback(async (): Promise<void> => {
    if (!getToken()) {
      setUser(null);
      return;
    }
    try {
      setUser(await authApi.me());
    } catch {
      // me 失败（含 401 已广播）：会话无效，交守卫跳登录
      clearToken();
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const signIn = useCallback((token: string, nextUser: AuthUser): void => {
    setToken(token);
    setUser(nextUser);
    setLoading(false);
    // F10：login/setup 响应不含 orgs（AuthService.UserView 仅 me 填充）——
    // 异步 refresh 一次补齐组织徽标数据源（验收抓获：徽标登录后缺失）
    void refresh();
  }, [refresh]);

  const signOut = useCallback(async (): Promise<void> => {
    try {
      await authApi.logout();
    } catch {
      // 登出失败不阻断本地清会话
    }
    clearToken();
    setUser(null);
  }, []);

  useEffect(() => {
    void refresh();
    const onUnauthorized = (): void => {
      setUser(null);
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [refresh]);

  const value = useMemo<AuthState>(
    () => ({ user, loading, refresh, signIn, signOut }),
    [user, loading, refresh, signIn, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) {
    throw new Error("useAuth 必须在 AuthProvider 内使用");
  }
  return state;
}
