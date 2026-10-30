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
  /** 后端 Long→String 序列化策略下实际为字符串，仅展示、不做运算。 */
  promptTokens?: number | string;
  completionTokens?: number | string;
  costMs?: number | string;
  createdAt: string;
}

export interface DocumentItem {
  id: Id;
  fileName: string;
  fileType: string | null;
  status: DocStatus;
  chunkCount: number | null;
  /** Long→String 策略下实际为字符串，仅展示。 */
  fileSize: number | string | null;
  errorMsg: string | null;
  /** ISO 字符串（后端 DocumentItemResponse.createdAt，v1.1 补齐），仅展示。 */
  createdAt: string | null;
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

/** POST /api/qa/stream 请求体（后端 QaStreamRequest 契约；kbId F11 线 G 起可空 = 意图路由）。 */
export interface QaStreamRequest {
  /** 可空：缺省 = 由意图路由决定目标库（02 §B10）。 */
  kbId?: Id | null;
  sessionId?: Id | null;
  question: string;
}

/** SSE 事件（概设 §3.4，仅本前端消费到的字段）。 */
export interface QaStart {
  runId: Id;
  messageId: Id;
  engine: string;
  /** F11 线 G additive：路由命中（KEYWORD/LLM）时携带；回落（FALLBACK）不携带。 */
  routedKbIds?: Id[];
  routedRouteName?: string | null;
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
  /** Long→String 策略下实际为字符串，仅展示。 */
  promptTokens: number | string | null;
  completionTokens: number | string | null;
  costMs: number | string | null;
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

// ===== 知识库 / Trace（v1.1 前端收口：GET /api/kb 与 /api/trace 已就绪的契约）=====

export interface KbSummary {
  id: Id;
  name: string;
  description: string | null;
}

export interface TraceRunItem {
  id: Id;
  bizType: string;
  bizId: Id | null;
  status: string;
  /** 后端 Long→String 序列化策略下实际为字符串（如 "4832"），展示时按需 Number()。 */
  totalCostMs: number | string | null;
  errorMsg: string | null;
  createdAt: string;
}

export interface TraceNodeItem {
  name: string;
  seq: number;
  costMs: number | string | null;
  status: string;
  error: string | null;
  input: string | null;
  output: string | null;
}

export interface TraceRunPage {
  items: TraceRunItem[];
  total: number;
  page: number;
  size: number;
}

export interface TraceRunDetail {
  run: TraceRunItem;
  nodes: TraceNodeItem[];
}

// ===== 认证（T6-0 契约：/api/auth/*；线 A 未合入前按 29-任务书 §1 mock 开发）=====

export interface AuthUser {
  id: Id;
  username: string;
  /** 线 A UserView 未提供该字段（联调对齐，DL-26）；展示一律回退 username。 */
  displayName?: string;
  systemRole: "ADMIN" | "USER";
}

export interface LoginResponse {
  token: string;
  user: AuthUser;
  /** 首启向导扩展（02 §B8 冻结契约）：仅 setup 且真实播种演示库时非空。 */
  seededKbId?: string | null;
}

/** GET /api/auth/setup-status（02 §B1 + §B8 冻结契约：seedAvailable=演示种子能力诚实呈现）。 */
export interface SetupStatus {
  needSetup: boolean;
  seedAvailable?: boolean;
}
