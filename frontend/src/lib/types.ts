/** 全局类型：所有 id 一律 string（红线：雪花 ID 超 2^53，绝不走 Number）。 */

export type Id = string;

export interface SessionItem {
  id: Id;
  title: string;
  createdAt: string;
}

export interface SourceRef {
  chunkId: Id;
  docName: string;
  score: number;
  snippet: string;
}

export interface MessageItem {
  id: Id;
  role: "user" | "assistant";
  content: string;
  engine?: string;
  sources: SourceRef[];
  promptTokens?: number;
  completionTokens?: number;
  costMs?: number;
  createdAt: string;
}

export interface DocumentItem {
  id: Id;
  fileName: string;
  fileType: string | null;
  fileSize: number | null;
  status: DocStatus;
  chunkCount: number | null;
  errorMsg: string | null;
  // GET /api/kb/{kbId}/documents 未返回 createdAt（后端契约现状），列表页暂以 chunkCount/状态为主
}

export type DocStatus =
  | "UPLOADED"
  | "PARSING"
  | "CHUNKING"
  | "EMBEDDING"
  | "COMPLETED"
  | "FAILED";

export const IN_FLIGHT_STATUSES: DocStatus[] = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"];

export function isInFlight(status: DocStatus): boolean {
  return IN_FLIGHT_STATUSES.includes(status);
}

/** POST /api/qa/stream 请求体（后端 QaStreamRequest 契约）。 */
export interface QaStreamRequest {
  kbId: Id;
  sessionId?: Id | null;
  question: string;
}

/** SSE 事件（概设 §3.4，仅本前端消费到的字段）。 */
export interface QaStart {
  runId: Id;
  messageId: Id;
  engine: string;
}
export interface QaDelta {
  delta: string;
}
export interface QaError {
  code: string;
  message: string;
}
export interface QaDone {
  messageId: Id | null;
  promptTokens: number | null;
  completionTokens: number | null;
  costMs: number | null;
}

// ===== Agent 事件（概设 §3.4，T3 契约；只消费，字段以 ReActEngine Ev* records 为准）=====

/** tool_call 事件：工具名 + 完整 arguments JSON 串。 */
export interface AgentToolCallEvent {
  toolName: string;
  arguments: string;
}

/** tool_result 事件：content 已被后端截断（契约注记：无耗时字段，前端自行计量）。 */
export interface AgentToolResultEvent {
  toolName: string;
  success: boolean;
  content: string;
}

/** confirm_request 事件：写操作挂起，等待用户批准/拒绝（随后当前流以 done 收尾）。 */
export interface AgentConfirmRequest {
  runId: Id;
  toolName: string;
  arguments: string;
}

/** cancel 事件：用户取消（终态语义），随后 done。 */
export interface AgentCancelEvent {
  messageId: Id | null;
}

// ===== 工具时间线（逐轮分组：一轮 LLM 推理 = 一组，详设 13-阶段3 §10）=====

export type ToolStepStatus = "running" | "success" | "failed" | "executed" | "rejected";

/** 时间线上的一个工具条目：call 开启、result/确认动作闭合。 */
export interface ToolStep {
  toolName: string;
  arguments: string;
  status: ToolStepStatus;
  result: string | null;
  startedAt: number;
  finishedAt: number | null;
}

export interface TimelineRound {
  /** 本轮推理的 thinking 累计（可折叠块）。 */
  thinking: string;
  steps: ToolStep[];
}

export interface AgentTimeline {
  rounds: TimelineRound[];
}
