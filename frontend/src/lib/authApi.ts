/**
 * 认证 API 门面（任务书 29-§1③）：线 A 未合入前走 authMock（VITE_AUTH_MOCK 未设或非
 * "false"），合流联调用 `VITE_AUTH_MOCK=false npm run dev` 切真实端点。
 *
 * 真实路径与 mock 同形：POST /api/auth/login|logout|setup、GET /api/auth/me|setup-status
 * （02-技术方案 §B1 契约）；loadDemoKb 的真实端点契约待线 A/D 对齐（联调清单见收工报告）。
 */
import { authHeaders, checkAuthFailure, UnauthorizedError } from "./auth";
import { authMock } from "./authMock";
import { parseResult } from "./json";
import * as v2Mock from "./v2Mock";
import type { AuthUser, LoginResponse, SetupStatus } from "./types";

export function isAuthMockEnabled(): boolean {
  return import.meta.env.VITE_AUTH_MOCK !== "false";
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, headers: { ...authHeaders(), ...init?.headers } });
  checkAuthFailure(response);
  if (!response.ok) {
    throw new Error(`请求失败：HTTP ${response.status}`);
  }
  return parseResult<T>(await response.text());
}

function postJson(body: unknown): RequestInit {
  return { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

export function setupStatus(): Promise<SetupStatus> {
  return isAuthMockEnabled() ? authMock.setupStatus() : request<SetupStatus>("/api/auth/setup-status");
}

export function login(username: string, password: string): Promise<LoginResponse> {
  return isAuthMockEnabled()
    ? authMock.login({ username, password })
    : request<LoginResponse>("/api/auth/login", postJson({ username, password }));
}

/** options.seedDemoKb：线 D ③ 契约占位——真实后端收 seed 标志随 setup 建演示库（本 worktree 未合入，忽略未知字段）。 */
export function setup(
  username: string,
  password: string,
  options?: { seedDemoKb?: boolean },
): Promise<LoginResponse> {
  const body = { username, password, ...(options?.seedDemoKb ? { seedDemoKb: true } : {}) };
  return isAuthMockEnabled()
    ? authMock.setup({ username, password })
    : request<LoginResponse>("/api/auth/setup", postJson(body));
}

export function me(): Promise<AuthUser> {
  if (isAuthMockEnabled()) {
    return authMock.me();
  }
  if (!authHeaders().Authorization) {
    return Promise.reject(new UnauthorizedError());
  }
  return request<AuthUser>("/api/auth/me");
}

export function logout(): Promise<void> {
  return isAuthMockEnabled() ? authMock.logout() : request<void>("/api/auth/logout", { method: "POST" });
}

/**
 * 加载演示库（F8 第二步「加载演示库」卡片，任务书 31-§1③）：demo 态由 v2Mock 起种子
 * 状态机；真实态 POST /api/kb/demo（M1 既定假设端点），进度轮询走 demoSeedProgress。
 */
export function loadDemoKb(): Promise<void> {
  if (isAuthMockEnabled()) {
    v2Mock.startSeed();
    return Promise.resolve();
  }
  return request<void>("/api/kb/demo", { method: "POST" });
}

/**
 * 演示库种子进度（任务书 31-§1③「种子进度轮询到 COMPLETED 后自动进聊天页」）。
 * 真实端点为假设契约 GET /api/demo/seed-progress——线 D ③ 未合入，联调对齐点（见收工报告）。
 */
export function demoSeedProgress(): Promise<{ status: string; docsDone: number; docsTotal: number }> {
  if (isAuthMockEnabled()) {
    return v2Mock.seedProgress();
  }
  return request<{ status: string; docsDone: number; docsTotal: number }>("/api/demo/seed-progress");
}
