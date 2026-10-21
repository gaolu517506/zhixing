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
