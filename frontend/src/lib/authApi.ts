/**
 * 认证 API 门面（任务书 29-§1③）：线 A 未合入前走 authMock（VITE_AUTH_MOCK 未设或非
 * "false"），合流联调用 `VITE_AUTH_MOCK=false npm run dev` 切真实端点。
 *
 * 真实路径与 mock 同形：POST /api/auth/login|logout|setup、GET /api/auth/me|setup-status
 * （02-技术方案 §B1 + §B8 冻结契约：setup 可选 seed=true 随建号播种演示库，响应
 * seededKbId 仅真实播种时非空；无独立种子触发/进度端点）。
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

/** options.seed：线 D 冻结契约——建号成功且种子可用时同步建演示库（seededKbId 非空）。 */
export function setup(username: string, password: string, options?: { seed?: boolean }): Promise<LoginResponse> {
  const body = { username, password, ...(options?.seed ? { seed: true } : {}) };
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
 * 加载演示库（F8 第二步，仅 demo 态）：v2Mock 起种子状态机供向导演示进度卡。
 * 真实态无独立端点（02 §B8 冻结契约：种子走 setup({seed:true})），调用即拒绝。
 */
export function loadDemoKb(): Promise<void> {
  if (isAuthMockEnabled()) {
    v2Mock.startSeed();
    return Promise.resolve();
  }
  return Promise.reject(new Error("真实环境请走 setup(seed:true)，无独立演示库端点"));
}

/** 演示库种子进度（仅 demo 态轮询）；真实态拒绝（同上）。 */
export function demoSeedProgress(): Promise<{ status: string; docsDone: number; docsTotal: number }> {
  if (isAuthMockEnabled()) {
    return v2Mock.seedProgress();
  }
  return Promise.reject(new Error("真实环境无种子进度端点"));
}
