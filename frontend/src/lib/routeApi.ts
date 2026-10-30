/**
 * 意图路由管理 API 门面（F11 线 G，契约 contracts/m3-org-intent-contracts.ts 线 G 段）。
 * 功能域拆线：后端同 worktree——无 mock 态，直连真实端点（单测 fetch stub）。
 */
import { authHeaders, checkAuthFailure } from "./auth";
import { parseResult } from "./json";

export interface IntentRoute {
  id: string;
  name: string;
  keywords: string[];
  examples: string[];
  targetKbIds: string[];
  enabled: boolean;
  createdAt: string;
}

export interface RouteTestResult {
  /** KEYWORD=关键词命中 / LLM=模型分类 / FALLBACK=回落 */
  matched: "KEYWORD" | "LLM" | "FALLBACK";
  routeId: string | null;
  routeName: string | null;
  score: number | null;
  routedKbIds: string[];
}

export interface RouteUpsert {
  name: string;
  keywords: string[];
  examples?: string[];
  targetKbIds: string[];
  enabled?: boolean;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, headers: { ...authHeaders(), ...init?.headers } });
  checkAuthFailure(response);
  if (!response.ok) {
    throw new Error(`请求失败：HTTP ${response.status}`);
  }
  return parseResult<T>(await response.text());
}

function json(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

export function listRoutes(): Promise<IntentRoute[]> {
  return request<IntentRoute[]>("/api/admin/routes");
}

export function createRoute(route: RouteUpsert): Promise<IntentRoute> {
  return request<IntentRoute>("/api/admin/routes", json("POST", route));
}

export function updateRoute(id: string, route: RouteUpsert): Promise<IntentRoute> {
  return request<IntentRoute>(`/api/admin/routes/${id}`, json("PUT", route));
}

export function deleteRoute(id: string): Promise<void> {
  return request<void>(`/api/admin/routes/${id}`, { method: "DELETE" });
}

export function testRoute(query: string): Promise<RouteTestResult> {
  return request<RouteTestResult>("/api/admin/routes/test", json("POST", { query }));
}
