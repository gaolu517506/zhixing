/**
 * 认证基础设施（任务书 29-§1③）：token 存储、Bearer 请求头、401 全局拦截信号。
 *
 * 职责刻意收窄为「无 React 依赖的底层」——api.ts 注入请求头并在 401 时广播事件，
 * AuthProvider 监听事件清会话，守卫据此重定向；三层互不反向依赖。
 */

/** localStorage key（收工报告约定的命名）。 */
export const TOKEN_KEY = "zhixing.auth.token";

/** 401 全局拦截事件名：任意请求 401 时由 api client 广播，AuthProvider 消费。 */
export const UNAUTHORIZED_EVENT = "zhixing:unauthorized";

export class UnauthorizedError extends Error {
  constructor(message = "登录已过期，请重新登录") {
    super(message);
    this.name = "UnauthorizedError";
  }
}

export function getToken(): string | null {
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY);
}

/** Bearer 请求头（未登录返回空对象，登录/首启端点本就在白名单）。 */
export function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** 广播 401：清 token 交给 AuthProvider（守卫监听 user 状态完成跳转）。 */
export function notifyUnauthorized(): void {
  clearToken();
  window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
}

/**
 * 统一响应检查：HTTP 401 → 广播 + 抛 UnauthorizedError（api.ts 全部出口共用，
 * SSE 流与上传同权——鉴权失败不因流式通道而走样）。
 */
export function checkAuthFailure(response: Response): void {
  if (response.status === 401) {
    notifyUnauthorized();
    throw new UnauthorizedError();
  }
}
