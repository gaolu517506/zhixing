/**
 * 验收向导 API 门面（F6，02-技术方案 §B6 冻结契约）：POST /api/eval/runs → GET /api/eval/runs/{id} 轮询。
 * demo 态（VITE_AUTH_MOCK!=="false"）走 v2Mock 状态机；联调态直连真实端点并做**单点视图映射**：
 * 后端 EvalRunView 的 config/report 为 JSONB 原样字符串（由前端解析）、时间字段名 createdAt。
 */
import { isAuthMockEnabled } from "./authApi";
import { authHeaders, checkAuthFailure } from "./auth";
import { parseResult } from "./json";
import * as mock from "./v2Mock";
import type { EvalReport, EvalRun } from "./v2Types";

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

/** 后端 EvalRunView 原始形态（02 §B6 冻结契约）：report 为 JSONB 字符串。 */
interface EvalRunViewRaw {
  id: string;
  kbId: string;
  createdBy?: string;
  status: string;
  config?: string;
  report?: string | null;
  errorMsg?: string | null;
  createdAt: string;
  finishedAt?: string | null;
}

/** report 字符串 → 对象；解析失败降级 null（报告步呈现「未返回报告」而非崩轮询）。 */
export function parseReport(raw: string | null | undefined): EvalReport | null {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as EvalReport;
  } catch {
    return null;
  }
}

/** EvalRunView → 前端 EvalRun 单点映射（字段改名 startedAt←createdAt + report 解析）。 */
export function mapEvalRun(view: EvalRunViewRaw): EvalRun {
  return {
    id: view.id,
    kbId: view.kbId,
    status: view.status,
    startedAt: view.createdAt,
    finishedAt: view.finishedAt ?? null,
    errorMsg: view.errorMsg ?? null,
    report: parseReport(view.report),
  };
}

export function startEvalRun(req: EvalStartRequest): Promise<EvalRun> {
  if (isAuthMockEnabled()) {
    return mock.startEvalRun(req.kbId, { setPath: req.setPath, setJson: req.setJson });
  }
  return request<EvalRunViewRaw>("/api/eval/runs", postJson(req)).then(mapEvalRun);
}

export function getEvalRun(id: string): Promise<EvalRun> {
  if (isAuthMockEnabled()) {
    return mock.getEvalRun(id);
  }
  return request<EvalRunViewRaw>(`/api/eval/runs/${id}`).then(mapEvalRun);
}
