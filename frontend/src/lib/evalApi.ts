/**
 * 验收向导 API 门面（F6，02-技术方案 §B6）：POST /api/eval/runs → GET /api/eval/runs/{id} 轮询。
 * demo 态（VITE_AUTH_MOCK!=="false"）走 v2Mock 状态机——线 D 端点未合入本 worktree（已实核）；
 * 联调态直连真实端点，契约不符由页面错误面承接并上报。
 */
import { isAuthMockEnabled } from "./authApi";
import { authHeaders, checkAuthFailure } from "./auth";
import { parseResult } from "./json";
import * as mock from "./v2Mock";
import type { EvalRun } from "./v2Types";

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

export interface EvalStartRequest {
  kbId: string;
  /** 二选一：后端评估集路径 或 内联 jsonl 文本 */
  setPath?: string;
  setJson?: string;
}

export function startEvalRun(req: EvalStartRequest): Promise<EvalRun> {
  if (isAuthMockEnabled()) {
    return mock.startEvalRun(req.kbId, { setPath: req.setPath, setJson: req.setJson });
  }
  return request<EvalRun>("/api/eval/runs", postJson(req));
}

export function getEvalRun(id: string): Promise<EvalRun> {
  if (isAuthMockEnabled()) {
    return mock.getEvalRun(id);
  }
  return request<EvalRun>(`/api/eval/runs/${id}`);
}
